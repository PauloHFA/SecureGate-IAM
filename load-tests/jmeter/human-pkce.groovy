import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.regex.Pattern
import groovy.json.JsonSlurper

// One sample is the whole journey, including parsing/consent; not token-endpoint latency.
def check = { boolean condition ->
  if (!condition) { throw new IllegalStateException('contract') }
}
def encode = { String text -> URLEncoder.encode(text, StandardCharsets.UTF_8) }
def base = new URI(props.getProperty('scheme', 'http'), null, props.getProperty('host', '127.0.0.1'),
    Integer.parseInt(props.getProperty('port', '8080')), '/', null, null)
def hidden = { String html, String name ->
  def inputs = Pattern.compile('<input\\b[^>]*>').matcher(html)
  while (inputs.find()) {
    def tag = inputs.group()
    if (tag.contains('name="' + name + '"')) {
      def value = Pattern.compile('value="([^"]*)"').matcher(tag)
      if (value.find()) {
        return value.group(1).replace('&amp;', '&').replace('&quot;', '"').replace('&#39;', "'")
      }
    }
  }
  throw new IllegalStateException('form_contract')
}
def localRedirect = { response ->
  URI target = base.resolve(response.headers().firstValue('Location').orElseThrow())
  check(target.scheme == base.scheme && target.host == base.host && target.port == base.port &&
      target.userInfo == null && target.fragment == null)
  return target
}
boolean journey = SampleResult.getSampleLabel() == 'pkce.journey.success'
HttpClient browser = journey ? null : vars.getObject('pkce_browser')
int status = 599
boolean keep = false
try {
  if (journey) {
    def previous = vars.getObject('pkce_browser')
    if (previous != null) { previous.close() }
    ['pkce_browser', 'pkce_code', 'pkce_verifier', 'pkce_basic', 'resource_token', 'resource_exp'].each { vars.remove(it) }
    browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build()
  }
  check(browser != null)
  def send = { String method, URI uri, Map form, String basic ->
    def builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
    if (basic != null) { builder.header('Authorization', 'Basic ' + basic) }
    if (method == 'POST') {
      def body = form.collect { key, value -> encode(key) + '=' + encode(value) }.join('&')
      builder.header('Content-Type', 'application/x-www-form-urlencoded')
          .POST(HttpRequest.BodyPublishers.ofString(body))
    } else { builder.GET() }
    def response = browser.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    status = response.statusCode()
    return response
  }
  if (journey) {
    byte[] entropy = new byte[32]
    new SecureRandom().nextBytes(entropy)
    String verifier = Base64.urlEncoder.withoutPadding().encodeToString(entropy)
    String challenge = Base64.urlEncoder.withoutPadding().encodeToString(
        MessageDigest.getInstance('SHA-256').digest(verifier.getBytes(StandardCharsets.US_ASCII)))
    String state = UUID.randomUUID().toString()
    String client = vars.get('client_id')
    String callback = vars.get('redirect_uri')
    def authorization = [response_type: 'code', client_id: client, redirect_uri: callback,
        scope: vars.get('scope'), state: state, code_challenge: challenge, code_challenge_method: 'S256']
    def query = authorization.collect { key, value -> encode(key) + '=' + encode(value) }.join('&')
    def initial = send('GET', base.resolve('/oauth2/authorize?' + query), [:], null)
    check(status == 302 && localRedirect(initial).path == '/login')
    def page = send('GET', base.resolve('/login'), [:], null)
    check(status == 200)
    def authenticated = send('POST', base.resolve('/login'),
        [username: vars.get('email'), password: vars.get('password'), _csrf: hidden(page.body(), '_csrf')], null)
    check(status == 302)
    def navigation = localRedirect(authenticated)
    check(navigation.path == '/oauth2/authorize')
    def consentNavigation = send('GET', navigation, [:], null)
    check(status == 302)
    URI returned = new URI(consentNavigation.headers().firstValue('Location').orElseThrow())
    if (returned.host == base.host && returned.port == base.port && returned.path == '/oauth2/consent') {
      def consentUri = localRedirect(consentNavigation)
      def consent = send('GET', consentUri, [:], null)
      check(status == 200)
      def approved = send('POST', base.resolve('/oauth2/authorize'),
          [client_id: client, state: hidden(consent.body(), 'state'), _csrf: hidden(consent.body(), '_csrf'), scope: vars.get('scope')], null)
      check(status == 302)
      returned = new URI(approved.headers().firstValue('Location').orElseThrow())
    }
    // Repeated journeys can reuse server-side consent; never follow the callback.
    check(returned.scheme == 'http' && returned.host == '127.0.0.1' && returned.port == 18090 &&
        returned.path == '/callback' && returned.userInfo == null && returned.fragment == null)
    def values = returned.rawQuery.split('&').collectEntries { part ->
      def pair = part.split('=', 2)
      [(URLDecoder.decode(pair[0], StandardCharsets.UTF_8)): URLDecoder.decode(pair[1], StandardCharsets.UTF_8)]
    }
    check(values.state == state && values.code instanceof String && !values.code.isBlank() && values.error == null)
    String basic = Base64.encoder.encodeToString((client + ':' + vars.get('client_secret')).getBytes(StandardCharsets.UTF_8))
    String submitted = props.getProperty('wrong_verifier', 'false') == 'true' ? verifier + '-wrong' : verifier
    def token = send('POST', base.resolve('/oauth2/token'), [grant_type: 'authorization_code',
        code: values.code, redirect_uri: callback, code_verifier: submitted], basic)
    check(status == 200)
    def body = new JsonSlurper().parseText(token.body())
    check(body.token_type?.equalsIgnoreCase('Bearer') && body.access_token instanceof String &&
        body.access_token ==~ /[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/ &&
        body.expires_in instanceof Number && body.expires_in > 0 && body.expires_in <= 120 && body.error == null)
    def claims = new JsonSlurper().parseText(new String(Base64.urlDecoder.decode(body.access_token.split('\\.')[1]), StandardCharsets.UTF_8))
    check(claims.sub == vars.get('user_id') && claims.client_id == client)
    vars.putObject('pkce_browser', browser)
    vars.put('pkce_code', values.code)
    vars.put('pkce_verifier', verifier)
    vars.put('pkce_basic', basic)
    if (props.getProperty('retain_resource_token', 'false') == 'true') {
      vars.put('resource_token', body.access_token)
      vars.put('resource_exp', claims.exp.toString())
    }
    keep = true
    SampleResult.setSuccessful(true)
    SampleResult.setResponseMessage('pkce_journey_completed')
  } else {
    def replay = send('POST', base.resolve('/oauth2/token'), [grant_type: 'authorization_code',
        code: vars.get('pkce_code'), redirect_uri: vars.get('redirect_uri'), code_verifier: vars.get('pkce_verifier')], vars.get('pkce_basic'))
    def body = new JsonSlurper().parseText(replay.body())
    check(status == 400 && body.error == 'invalid_grant' && body.access_token == null)
    SampleResult.setSuccessful(true)
    SampleResult.setResponseMessage('expected_code_replay_rejected')
  }
} catch (Exception ignored) {
  SampleResult.setSuccessful(false)
  SampleResult.setResponseMessage('pkce_contract_failed')
} finally {
  SampleResult.setResponseCode(Integer.toString(status))
  SampleResult.setResponseData(new byte[0])
  SampleResult.setSamplerData('redacted')
  SampleResult.setRequestHeaders('redacted')
  SampleResult.setResponseHeaders('redacted')
  if (!keep) {
    if (browser != null) { browser.close() }
    ['pkce_browser', 'pkce_code', 'pkce_verifier', 'pkce_basic', 'resource_token', 'resource_exp'].each { vars.remove(it) }
  }
}
