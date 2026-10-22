import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def check = { boolean condition ->
  if (!condition) { throw new IllegalStateException('audit_contract_mismatch') }
}
def clear = {
  ['audit_users', 'audit_correlation', 'audit_from', 'audit_to', 'audit_cursor', 'audit_first'].each { vars.remove(it) }
}
def base = new URI(props.getProperty('scheme', 'http'), null, props.getProperty('host', '127.0.0.1'),
    Integer.parseInt(props.getProperty('port', '8080')), '/', null, null)
String label = SampleResult.getSampleLabel()
int status = 599
boolean failed = true
HttpClient client = null
try {
  client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
      .followRedirects(HttpClient.Redirect.NEVER).build()
  String admin = Files.readString(Path.of(props.getProperty('admin_token_file'))).trim()

  def send = { String path, String body, String correlation ->
    def builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5))
    if (admin != null) { builder.header('Authorization', 'Bearer ' + admin) }
    if (correlation != null) { builder.header('X-Correlation-Id', correlation) }
    if (body == null) { builder.GET() } else {
      builder.header('Content-Type', 'application/json').POST(HttpRequest.BodyPublishers.ofString(body))
    }
    def response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    status = response.statusCode()
    return response
  }

  def queryParameters = {
    check(vars.get('audit_correlation') != null)
    [from: vars.get('audit_from'), to: vars.get('audit_to'), eventType: 'identity.user.created',
        correlationId: vars.get('audit_correlation'), limit: '1']
  }

  def page = { Map queryValues ->
    String query = queryValues.collect { k, v -> URLEncoder.encode(k, StandardCharsets.UTF_8) + '=' +
        URLEncoder.encode(v, StandardCharsets.UTF_8) }.join('&')
    def response = send('/api/admin/audit/events?' + query, null, null)
    check(status == 200)
    def body = new JsonSlurper().parseText(response.body())
    check(body.items instanceof List && body.items.size() <= 1)
    return body
  }

  def validate = { item ->
    check(item.id == item.event.eventId && item.event.eventType == 'identity.user.created' &&
        item.event.correlationId == vars.get('audit_correlation') &&
        item.event.producer == 'iam-core' && item.event.schemaVersion == 1 &&
        vars.getObject('audit_users').contains(item.event.context.resource))
    Instant occurred = Instant.parse(item.occurredAt)
    check(!occurred.isBefore(Instant.parse(vars.get('audit_from'))) &&
        occurred.isBefore(Instant.parse(vars.get('audit_to'))) && item.receivedAt != null)
  }

  if (label == 'audit.produce.call') {
    String correlation = vars.get('audit_correlation')
    Set users = (Set) vars.getObject('audit_users')
    if (correlation == null || users == null) {
      clear()
      correlation = 'audit-' + UUID.randomUUID()
      Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
      vars.put('audit_from', now.minusSeconds(10).toString())
      vars.put('audit_to', now.plusSeconds(300).toString())
      vars.put('audit_correlation', correlation)
      users = new HashSet()
      vars.putObject('audit_users', users)
    }
    byte[] entropy = new byte[24]
    new SecureRandom().nextBytes(entropy)
    String password = 'Audit!' + Base64.urlEncoder.withoutPadding().encodeToString(entropy)
    String email = 'audit-' + UUID.randomUUID() + '@example.test'
    def response = send('/api/admin/users', JsonOutput.toJson(
        [email: email, displayName: 'Synthetic audit baseline user', password: password]), correlation)
    check(status == 201)
    def user = new JsonSlurper().parseText(response.body())
    check(user.id instanceof String && user.id ==~ /[a-f0-9-]{36}/ && user.email == email)
    check(users.add(user.id))
    SampleResult.setResponseMessage('user_created_for_audit')
  } else if (label == 'audit.visibility.observed') {
    check(vars.getObject('audit_users')?.size() == 2)
    Map query = queryParameters()
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (true) {
      def body = page(query)
      if (body.items.size() == 1 && body.nextCursor instanceof String && !body.nextCursor.isBlank()) {
        validate(body.items[0])
        vars.putObject('audit_first', body.items[0])
        vars.put('audit_cursor', body.nextCursor)
        break
      }
      check(System.nanoTime() < deadline)
      Thread.sleep(25)
    }
    SampleResult.setResponseMessage('audit_visible_in_store')
  } else if (label == 'audit.cursor.query') {
    check(vars.get('audit_cursor') != null)
    Map query = queryParameters()
    query.cursor = vars.get('audit_cursor')
    def body = page(query)
    check(body.items.size() == 1 && body.nextCursor == null)
    def second = body.items[0]
    validate(second)
    def first = vars.getObject('audit_first')
    check(first.id != second.id &&
        ([first.event.context.resource, second.event.context.resource] as Set) == vars.getObject('audit_users'))
    check(!Instant.parse(second.occurredAt).isAfter(Instant.parse(first.occurredAt)))
    SampleResult.setResponseMessage('audit_cursor_query_success')
  } else {
    check(false)
  }
  failed = false
  SampleResult.setSuccessful(true)
} catch (Exception ignored) {
  SampleResult.setSuccessful(false)
  SampleResult.setResponseMessage('audit_contract_failed')
} finally {
  SampleResult.setResponseCode(Integer.toString(status))
  SampleResult.setResponseData(new byte[0])
  SampleResult.setSamplerData('redacted')
  SampleResult.setRequestHeaders('redacted')
  SampleResult.setResponseHeaders('redacted')
  if (client != null) { client.close() }
  if (failed || label == 'audit.cursor.query') { clear() }
}

