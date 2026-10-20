[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidatePattern('^[0-9]{4}-(0[1-9]|1[0-2])$')][string]$Month,
  [ValidateRange(2, 120)][int]$HotMonths = 6,
  [switch]$Apply
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LocalEnvironment.ps1')
$archiveDate = [DateTime]::ParseExact($Month + '-01', 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
if ($archiveDate.Year -lt 1970 -or $archiveDate.Year -ge 9998) { throw 'Mes de arquivo fora do intervalo permitido.' }
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$settings = Read-LocalEnvironment (Join-Path $projectPath '.env')
$applicationEnvironment = @{ PGPASSWORD = $settings.POSTGRES_PASSWORD; AUDIT_ARCHIVE_MONTH = $Month + '-01'; AUDIT_HOT_MONTHS = [string]$HotMonths }
$previousValues = @{}
$prefix = @'
SET client_min_messages=warning;
SET lock_timeout='2s';
SET statement_timeout='120s';
SET idle_in_transaction_session_timeout='10s';
SET TIME ZONE 'UTC';
\getenv archive_month AUDIT_ARCHIVE_MONTH
\getenv hot_months AUDIT_HOT_MONTHS
'@
$operation = if ($Apply) {
  "SELECT :'archive_month'::date AS month, iam_audit.archive_event_month(:'archive_month'::date, :'hot_months'::integer) AS archived_rows;"
} else {
  @'
SELECT :'archive_month'::date AS month, :'hot_months'::integer AS hot_months,
  :'archive_month'::date < (date_trunc('month',clock_timestamp() AT TIME ZONE 'UTC')-((:'hot_months'::integer-1)*INTERVAL '1 month'))::date AS eligible,
  to_regclass('iam_audit.event_log_' || to_char(:'archive_month'::date,'YYYYMM')) IS NOT NULL AS source_exists,
  EXISTS(SELECT 1 FROM iam_audit.archived_months WHERE month=:'archive_month'::date) AS already_archived;
'@
}
try {
  foreach ($key in $applicationEnvironment.Keys) {
    $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
    [Environment]::SetEnvironmentVariable($key, $applicationEnvironment[$key], 'Process')
  }
  ($prefix + "`n" + $operation) | & docker compose --env-file (Join-Path $projectPath '.env') -f (Join-Path $projectPath 'infra/docker-compose.yml') exec -T -e PGPASSWORD -e AUDIT_ARCHIVE_MONTH -e AUDIT_HOT_MONTHS postgres psql -X -v ON_ERROR_STOP=1 -U $settings.POSTGRES_USER -d $settings.POSTGRES_DB
  if ($LASTEXITCODE -ne 0) { throw 'Operacao de arquivo recusada; confira V16, elegibilidade e locks. Nenhum dado deve ser removido manualmente.' }
  Write-Output $(if ($Apply) { 'Arquivamento confirmado no PostgreSQL; recibos preservados. Nao e backup externo.' } else { 'Somente consulta do plano. Use -Apply para executar o arquivamento administrativo.' })
} finally {
  foreach ($key in $previousValues.Keys) { [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process') }
}
