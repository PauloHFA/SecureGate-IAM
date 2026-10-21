# JMeter — 10A2b3: ingestão e consulta de auditoria

## Objetivo e pré-requisitos

Plano funcional: produzir fatos reais → consultar o log central por correlação/tipo → continuar por cursor → confirmar recusa sem credencial. Java 21, PowerShell Windows, JMeter 5.6.3 instalado/conferido conforme a [base](README.md) e Docker Desktop para o teste isolado. Windows foi exercitado; Linux/PowerShell 7 não foi validado.

## Smoke isolado

Na raiz, adaptar o caminho da distribuição JMeter:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterAuditSmoke,JMeterResourceRevocationSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' verify
```

JMeterAuditSmoke é opt-in; não exige JMeter no build padrão. A seleção inclui a regressão de recurso/revogação da 10A2b2. A fixture usa PostgreSQL/Redis/RabbitMQ/Vault descartáveis e JARs IAM/enforcer reais. Um helper específico provisiona os logins restritos de auditoria pelo script administrativo existente e reinicia o IAM com consumidor/consulta; não habilita PAM, não altera a stack persistente e não usa credenciais de .env. Partições correntes vêm das migrações da base descartável; não é teste de manutenção de meses futuros.

Duas threads/uma iteração produzem quatro usuários/eventos e oito amostras. O teste exige as duas páginas de cada correlação e os contratos abaixo. Controle com WrongCorrelation cria outros quatro usuários/fatos, mas consulta uma correlação ausente: HTTP 200 vazio deve falhar, cursor dependente falha localmente e recusa sem credencial continua aprovada. Fora do cronômetro, o teste aguarda oito eventos centrais/publicados e compara o payload de cada envelope com a Outbox. Não injeta fatos diretamente no banco/fila nem drena filas.

Artefatos em integration-tests/target/audit-smoke/<execução>/. Arquivo administrativo removido em finally; encerramento forçado pode deixá-lo ignorado e exigir limpeza local. Logs dos serviços em target/e2e não são automaticamente sanitizados/publicáveis.

## Stack manual dedicada

Preparar [identidades de auditoria](../../docs/runbooks/identidades-auditoria-local.md), partições e infraestrutura conforme os runbooks existentes. Iniciar IAM em terminal separado:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableOutboxPublisher -EnableAuditConsumer -EnableAuditPartitions -EnableAuditQuery
```

Este plano não precisa de Authorization/PKCE/enforcer para gerar/consultar os fatos escolhidos. O teste de integração mantém o ambiente completo já usado pelos outros smokes. Em outro terminal:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$loadSettings = Read-LocalEnvironment (Join-Path $PWD '.env')
[IO.Directory]::CreateDirectory((Join-Path $PWD 'load-tests/.local')) | Out-Null
$loadTokenFile = Join-Path $PWD 'load-tests/.local/audit-admin-token'
[IO.File]::WriteAllText($loadTokenFile, $loadSettings.IAM_BOOTSTRAP_TOKEN)
try {
  .\load-tests\jmeter\Run-Audit.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -AdminTokenFile $loadTokenFile -OutputDirectory load-tests/.local/audit-run-001
} finally {
  Remove-Item -LiteralPath $loadTokenFile
  $loadSettings = $null
}
```

O runner passa somente o caminho do arquivo administrativo para o JMeter; não passa o token na linha de comando. O plano lê a credencial em memória e usa HTTP sem cookies/redirects. Somente loopback sem caminho/query/credenciais é aceito. Não sobrescreve diretório de saída. Preservar permissões do arquivo administrativo/diretórios e não habilitar debug HTTP/listeners extras.

## Massa e efeitos persistentes

Cada iteração cria **dois usuários novos** por POST /api/admin/users, com e-mail audit-<UUID>@example.test, senha CSPRNG de 24 bytes e correlação audit-<UUID> comum às duas operações. Estrutura/quantidade são repetíveis; IDs/senhas/tempos variam. Nenhuma senha/CSV de usuários é persistida pelo plano. Usuários ficam ativos na instância, e fatos permanecem na Outbox/log. Não há rollback/remoção automática; usar stack dedicada e descartável. Falha parcial pode deixar um usuário/fato, e repetir a execução cria novos registros.

A janela por iteração é fixa [início−5 s, início+300 s), em UTC com precisão de milissegundos. Usa eventType=identity.user.created, correlationId exato e limit=1; mantém janela/filtros na continuação. Usuários/cursores/primeiro item ficam somente em memória da thread e são removidos ao final/falha.

## Contratos e unidades

| Rótulo | Contrato | Unidade |
| --- | --- | --- |
| audit.produce.success | Dois POSTs com HTTP 201 e IDs distintos; API/Outbox reais | Agregado de criação, incluindo hashing de senha; não mede ingestão isolada. |
| audit.visible.expected | API central retorna 200, um item e nextCursor não vazio; envelope/sujeito/correlação/tipo/janela conferem | Polling até o primeiro item com continuação, intervalo 100 ms e prazo 10 s mais chamada em curso (timeout 5 s). |
| audit.cursor.success | Segunda página tem um evento distinto, sem nextCursor; recursos das duas páginas correspondem exatamente aos usuários criados | Uma chamada com cursor real; valida também ordem temporal decrescente. |
| audit.unauthorized.expected | Consulta sem Authorization recebe 401 | Uma chamada; recusa esperada é sucesso. |

No polling, 200 vazio/incompleto permite repetir; 400/401/403/404/429/503/redirect falham imediatamente. O código local 599 indica pré-condição/erro de cliente, não resposta do servidor. Se a visibilidade falhar, não se inventa cursor; o sampler dependente falha localmente. O caso sem credencial é independente.

## Runner e controle

Padrão: uma thread/uma iteração/ramp-up de 1 s → dois usuários e quatro amostras. Limites: 1–20 threads, 1–100 iterações, 1–60 s de ramp-up. usersPlanned em run.json é quantidade planejada (2 × threads × loops), não confirmação de gravações após falha. Mais concorrência aumenta também hashing, gravações e polling; não há controle de taxa constante.

Gera samples.csv, summary.csv, dashboard/index.html, run.json e jmeter.log no perfil safe-results.properties. Exige quatro rótulos, threads × loops amostras por rótulo e zero falhas; processo JMeter com exit code zero não aprova contratos quebrados. Não salva corpos, cabeçalhos, cursores, e-mails, senhas, envelopes ou token administrativo nos resultados.

Para provar detecção, repetir com -WrongCorrelation em diretório novo. Fatos são criados normalmente, mas a primeira consulta usa uma correlação ausente; audit.visible.expected deve falhar após respostas 200 vazias, e audit.cursor.success deve falhar sem chamada HTTP. O teste exige esse resultado e não trata como falha operacional pendente.

## Limites

É smoke funcional da cadeia de auditoria, com concorrência pequena. elapsed inclui HttpClient/parsing; polling inclui espera/publicação/consumo/consulta, sem decompor essas latências. Latency/Connect/bytes JSR223 não são instrumentados. Não mede taxa máxima de ingestão, p95, 300 req/s, estabilidade, tracing, uso de recursos ou SLA. Não testa retenção/arquivo, deduplicação/replay/conflito, indisponibilidade/recuperação ou todos os tipos/filtros de eventos. A API não oferece snapshot; o cenário estabiliza apenas duas operações por correlação aleatória.

A 10A reúne agora smokes dos cenários previstos. Ensaios de capacidade e o aceite global da etapa 10 dependem de 10B (observabilidade), 10C (medição) e 10D (ajustes).

Referências: [ADR-049](../../docs/adr/049-carga-de-auditoria-central.md), [evidências](../../docs/evidencias/etapa-10a2b3.md), [runbook](../../docs/runbooks/carga-auditoria-local.md), [consulta](../../docs/runbooks/consulta-auditoria-local.md), [guia](../../docs/GUIA-DE-IMPLEMENTACAO.md).
