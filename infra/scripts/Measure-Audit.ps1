[CmdletBinding()]
param([ValidateRange(3000, 5000000)][int]$Rows = 2000000)

$ErrorActionPreference = 'Continue'
$projectPath = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $projectPath
try {
  Write-Output "Benchmark optativo: $Rows eventos sinteticos; PostgreSQL descartavel com limite de 4 CPUs/4 GiB. A stack persistente nao sera usada."
  & .\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=NoBenchmarkUnitTests' '-Dit.test=AuditPerformanceBenchmark' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-DargLine=-Xmx1g' "-Daudit.benchmark.rows=$Rows" verify
  if ($LASTEXITCODE -ne 0) { throw 'Benchmark falhou. Confira logs e Docker; nao use resultados parciais como medicao completa.' }
  Write-Output 'Resultados: iam-core/target/audit-benchmark/summary.json, samples.csv e plans/. Preserve-os antes de executar clean.'
} finally { Pop-Location }
