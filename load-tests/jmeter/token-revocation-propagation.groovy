import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

def check = { boolean condition ->
  if (!condition) { throw new IllegalStateException('revocation_contract_mismatch') }
}
def base = new URI(props.getProperty('scheme', 'http'), null, props.getProperty('host', '127.0.0.1'),
    Integer.parseInt(props.getProperty('port', '8080')), '/', null, null)
int status = 599
HttpClient client = null
try {
  String token = vars.get('resource_token')
  check(token != null)
  long expires = Long.parseLong(vars.get('resource_exp'))
  check(expires > Instant.now().epochSecond + 5)
  client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
      .followRedirects(HttpClient.Redirect.NEVER).build()
  long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
  while (true) {
    def response = client.send(HttpRequest.newBuilder(base.resolve('/api/resources/account'))
        .timeout(Duration.ofSeconds(3)).header('Authorization', 'Bearer ' + token).GET().build(),
        HttpResponse.BodyHandlers.ofString())
    status = response.statusCode()
    // A natural expiry is never accepted as proof of revocation
    check(expires > Instant.now().epochSecond + 5)
    if (status == 401) { break }
    check(status == 200 && System.nanoTime() < deadline)
    Thread.sleep(20)
  }
  SampleResult.setSuccessful(true)
  SampleResult.setResponseMessage('revocation_propagated_and_observed')
} catch (Exception ignored) {
  SampleResult.setSuccessful(false)
  SampleResult.setResponseMessage('revocation_observation_failed')
} finally {
  SampleResult.setResponseCode(Integer.toString(status))
  SampleResult.setResponseData(new byte[0])
  SampleResult.setSamplerData('redacted')
  SampleResult.setRequestHeaders('redacted')
  SampleResult.setResponseHeaders('redacted')
  if (client != null) { client.close() }
  def browser = vars.getObject('pkce_browser')
  if (browser != null) { browser.close() }
  ['pkce_browser', 'pkce_code', 'pkce_verifier', 'pkce_basic', 'resource_token', 'resource_exp'].each { vars.remove(it) }
}

