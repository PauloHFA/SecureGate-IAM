import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import groovy.json.JsonSlurper

// No browser cookies: every protected request must authenticate solely with its Bearer token.
def check = { boolean condition ->
  if (!condition) { throw new IllegalStateException('contract') }
}
def base = new URI(props.getProperty('scheme', 'http'), null, props.getProperty('host', '127.0.0.1'),
    Integer.parseInt(props.getProperty('port', '8080')), '/', null, null)
String label = SampleResult.getSampleLabel()
int status = 599
boolean failed = true
HttpClient client = null
try {
  String token = vars.get('resource_token')
  check(token != null && vars.getObject('pkce_browser') != null)
  long expires = Long.parseLong(vars.get('resource_exp'))
  check(expires > Instant.now().epochSecond + 15)
  client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
      .followRedirects(HttpClient.Redirect.NEVER).build()
  def resource = { String bearer ->
    def response = client.send(HttpRequest.newBuilder(base.resolve('/api/resources/account'))
        .timeout(Duration.ofSeconds(3)).header('Authorization', 'Bearer ' + bearer).GET().build(),
        HttpResponse.BodyHandlers.ofString())
    status = response.statusCode()
    return response
  }
  if (label == 'resource.success') {
    def response = resource(token)
    check(status == 200)
    def body = new JsonSlurper().parseText(response.body())
    check(body.sub == vars.get('user_id'))
  } else if (label == 'resource.invalid_token.expected') {
    resource('deliberately-invalid-synthetic-token')
    check(status == 401)
  } else if (label == 'token.revoke.success') {
    if (props.getProperty('skip_revocation', 'false') == 'true') {
      // Fault injection: deliberately send no revocation. The next sample MUST fail.
      status = 599
      SampleResult.setResponseMessage('revocation_intentionally_skipped')
    } else {
      String form = 'token=' + URLEncoder.encode(token, StandardCharsets.UTF_8) + '&token_type_hint=access_token'
      def response = client.send(HttpRequest.newBuilder(base.resolve('/oauth2/revoke'))
          .timeout(Duration.ofSeconds(5)).header('Authorization', 'Basic ' + vars.get('pkce_basic'))
          .header('Content-Type', 'application/x-www-form-urlencoded')
          .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString())
      status = response.statusCode()
      check(status == 200)
    }
  } else if (label == 'resource.revoked.expected') {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (true) {
      resource(token)
      // A natural expiry is never accepted as proof of revocation.
      check(expires > Instant.now().epochSecond + 5)
      if (status == 401) { break }
      check(status == 200 && System.nanoTime() < deadline)
      Thread.sleep(100)
    }
  } else { check(false) }
  failed = false
  SampleResult.setSuccessful(true)
  if (SampleResult.getResponseMessage() != 'revocation_intentionally_skipped') {
    SampleResult.setResponseMessage('resource_contract_passed')
  }
} catch (Exception ignored) {
  SampleResult.setSuccessful(false)
  SampleResult.setResponseMessage('resource_contract_failed')
} finally {
  SampleResult.setResponseCode(Integer.toString(status))
  SampleResult.setResponseData(new byte[0])
  SampleResult.setSamplerData('redacted')
  SampleResult.setRequestHeaders('redacted')
  SampleResult.setResponseHeaders('redacted')
  if (client != null) { client.close() }
  if (failed || label == 'resource.revoked.expected') {
    def browser = vars.getObject('pkce_browser')
    if (browser != null) { browser.close() }
    ['pkce_browser', 'pkce_code', 'pkce_verifier', 'pkce_basic', 'resource_token', 'resource_exp'].each { vars.remove(it) }
  }
}
