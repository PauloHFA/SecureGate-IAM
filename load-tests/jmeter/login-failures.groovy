import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.regex.Pattern

// One sample is a complete rejected login, including CSRF and the anonymous-session check.
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
boolean known = SampleResult.getSampleLabel() == 'login.invalid_password.expected'
if (known) { vars.remove('generic_login_failure') }
HttpClient browser = null
int status = 599
String failure = 'login_contract_failed'
try {
  if (!known) { check(vars.get('generic_login_failure') != null) }
  browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
      .followRedirects(HttpClient.Redirect.NEVER)
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)).build()
  def send = { String method, URI uri, Map form ->
    def builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
    if (method == 'POST') {
      String body = form.collect { key, value -> encode(key) + '=' + encode(value) }.join('&')
      builder.header('Content-Type', 'application/x-www-form-urlencoded')
          .POST(HttpRequest.BodyPublishers.ofString(body))
    } else { builder.GET() }
    def response = browser.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    status = response.statusCode()
    return response
  }
  def login = send('GET', base.resolve('/login'), [:])
  check(status == 200 && login.body().contains('name="username"'))
  String password = vars.get('password') + '-incorrect'
  if (known && props.getProperty('use_correct_password', 'false') == 'true') { password = vars.get('password') }
  String email = vars.get(known ? 'email' : 'unknown_email')
  def attempted = send('POST', base.resolve('/login'),
      [username: email, password: password, _csrf: hidden(login.body(), '_csrf')])
  check(status == 302)
  URI destination = localRedirect(attempted)
  if (destination != base.resolve('/login?error')) {
    def authenticated = send('GET', base.resolve('/login'), [:])
    if (status == 200 && authenticated.body().contains('action="/logout"') &&
        !authenticated.body().contains('name="username"')) { failure = 'unexpected_authentication' }
    throw new IllegalStateException('rejection_missing')
  }
  def rejected = send('GET', destination, [:])
  check(status == 200 && rejected.body().contains('name="username"'))
  ['email', 'unknown_email', 'user_id', 'password'].each { key -> check(!rejected.body().contains(vars.get(key))) }
  def alert = Pattern.compile('<p\\s+role="alert">([^<]+)</p>').matcher(rejected.body())
  check(alert.find())
  String message = alert.group(1)
  if (known) { vars.put('generic_login_failure', message) }
  else { check(message == vars.get('generic_login_failure')) }
  // An error redirect alone must not hide an authenticated browser session.
  def protectedPage = send('GET', base.resolve('/mfa'), [:])
  check(status == 302 && localRedirect(protectedPage).path == '/login')
  SampleResult.setSuccessful(true)
  SampleResult.setResponseMessage('expected_login_failure')
} catch (Exception ignored) {
  SampleResult.setSuccessful(false)
  SampleResult.setResponseMessage(failure)
  vars.remove('generic_login_failure')
} finally {
  if (!known) { vars.remove('generic_login_failure') }
  SampleResult.setResponseCode(Integer.toString(status))
  SampleResult.setResponseData(new byte[0])
  SampleResult.setSamplerData('redacted')
  SampleResult.setRequestHeaders('redacted')
  SampleResult.setResponseHeaders('redacted')
  if (browser != null) { browser.close() }
}
