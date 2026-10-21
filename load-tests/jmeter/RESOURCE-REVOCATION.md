# JMeter — 10A2b2: recurso protegido e revogação

## Objetivo e pré-requisitos

Jornada humana PKCE → recurso protegido → token inválido → revogação OAuth → recusa do token ainda não expirado. Java 21, PowerShell Windows, JMeter 5.6.3 instalado/conferido conforme [base JMeter](README.md) e Docker Desktop para o teste isolado. Windows é a plataforma exercitada; Linux/PowerShell 7 não foi validado.

## Smoke isolado

Na raiz, adaptar somente o caminho da distribuição JMeter:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterResourceRevocationSmoke,JMeterHumanSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' verify
```

A seleção executa o novo plano e a regressão do PKCE compartilhado. Classes Smoke são opt-in; o build padrão não exige JMeter. A fixture sobe PostgreSQL/Redis/RabbitMQ/Vault descartáveis e JARs IAM/enforcer reais. Configura rede/perfil de risco sintético apenas na fixture, cria dois usuários/clientes e políticas por API. Duas threads/uma iteração geram dez amostras. Após a execução, o teste confirma publicação de dois fatos token.revoked e projeção no Redis fora do cronômetro JMeter. Executa também o controle sem revogar, que deve falhar na recusa posterior com HTTP 200 real. Confere ausência de senha, Basic, token administrativo, identificadores humanos e JWT nos artefatos textuais.

Resultados em integration-tests/target/resource-revocation-smoke/<execução>/. Token administrativo/CSV são removidos em finally; encerramento forçado pode deixá-los no diretório ignorado. Logs dos serviços em target/e2e não são automaticamente sanitizados/publicáveis.

## Uso manual em stack dedicada

Preparar [infraestrutura e AppRole](../../infra/README.md). Em terminais separados, iniciar:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap -EnableResourceEnforcement -EnableOutboxPublisher
.\infra\scripts\Start-PolicyEnforcer.ps1 -EnableConsumer
```

Os dois comandos representam processos separados. Não iniciar uma segunda instância se a stack já estiver pronta. O login deve passar pela política de risco real sem MFA adicional; os scripts não alteram risco/MFA nem limpam Redis. O teste opt-in oferece a preparação isolada reproduzível.

Em outro terminal:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$loadSettings = Read-LocalEnvironment (Join-Path $PWD '.env')
[IO.Directory]::CreateDirectory((Join-Path $PWD 'load-tests/.local')) | Out-Null
$loadTokenFile = Join-Path $PWD 'load-tests/.local/admin-token'
[IO.File]::WriteAllText($loadTokenFile, $loadSettings.IAM_BOOTSTRAP_TOKEN)
try {
  .\load-tests\jmeter\New-HumanMass.ps1 -BaseUri http://127.0.0.1:8080/ -AdminTokenFile $loadTokenFile -OutputFile load-tests/.local/resources.csv -Prefix load-resource-manual -Count 1 -IncludeAccountPolicy
} finally {
  Remove-Item -LiteralPath $loadTokenFile
  $loadSettings = $null
}
.\load-tests\jmeter\Run-ResourceRevocation.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -MassFile load-tests/.local/resources.csv -OutputDirectory load-tests/.local/resource-run-001
```

IncludeAccountPolicy acrescenta uma política ALLOW_LIMITED para account:self/read com api:read, TTL máximo 120 s e rolesAnyOf vazio. Ela se aplica aos sujeitos elegíveis da instância, sem isolamento por usuário; usar exclusivamente ambiente dedicado de testes. A política de emissão continua limitada ao cliente criado, audience securegate-api e TTL máximo 120 s. Sem esse switch, New-HumanMass mantém o comportamento anterior. Falha parcial não desfaz registros nem políticas; escolher namespace novo ou recriar stack descartável. CSV privado tem o formato humano já documentado em [HUMAN-PKCE.md](HUMAN-PKCE.md), só pode ser escrito nas raízes privadas permitidas e não é sobrescrito. Nenhum JWT vai ao CSV; token fica na memória da thread e é removido ao final/falha.

## Contratos

| Rótulo | Contrato | Unidade medida |
| --- | --- | --- |
| pkce.journey.success | Login/CSRF/cookies/consentimento/S256 reais, token com sujeito esperado | Jornada agregada PKCE reutilizada da 10A2a. |
| resource.success | GET /api/resources/account retorna 200 e sub esperado | Uma chamada Bearer, cliente sem cookies. |
| resource.invalid_token.expected | Mesmo recurso com token sintético malformado retorna 401 | Uma chamada; recusa esperada é sucesso. |
| token.revoke.success | POST /oauth2/revoke autenticado pelo cliente retorna 200 | Uma chamada; 200 sozinho não prova invalidação. |
| resource.revoked.expected | Reconsulta com token original retorna 401 antes da expiração | Polling agregado, intervalo 100 ms, prazo 10 s mais chamada em curso (timeout 3 s). |

No polling, somente 200 permite repetir; 403/429/503/redirect e demais códigos falham imediatamente. Exige mais de 15 s de validade antes de cada cenário e mais de 5 s ao observar a recusa, evitando confundir expiração natural com revogação. Se preparação/contrato anterior falhar, estado privado é limpo e cenários dependentes falham localmente com 599. Não há fallback nem desativação de controles.

O IAM consulta também o grant/ledger local: HTTP 401 pode ocorrer antes da entrega no RabbitMQ/Redis. O plano HTTP **não isola o tempo de propagação distribuída**. A fixture verifica publicação/projeção separadamente. Não equivale a ensaio de resource server externo, logout/revogação de sessão, refresh token, indisponibilidade ou teste de assinatura adulterada.

## Runner, controle e interpretação

Padrão: uma thread/uma iteração/ramp-up 1 s. Limites: 1–20 threads, 1–100 iterações, 1–60 s de ramp-up; massa com pelo menos uma linha por thread. CSV compartilhado/reciclado não fixa usuário por thread. Só aceita URI loopback sem caminho/query/credenciais. Novo diretório de saída obrigatório.

Gera samples.csv, summary.csv, dashboard/index.html, run.json e jmeter.log com o mesmo perfil de redação da base. Exige cinco rótulos, contagem exata threads × loops por rótulo e zero falhas. Tokens/corpos/headers não são persistidos. Não habilitar debug/listeners extras. Preservar permissões dos diretórios privados e remover massa quando não for necessária.

Para provar detecção, executar em diretório novo com -SkipRevocation. O sampler token.revoke.success vira controle local (599, revocation_intentionally_skipped) sem chamada HTTP; resource.revoked.expected deve falhar após o prazo com token ainda aceito (200). Esse modo nunca é evidência de execução normal. A fixture exige exatamente esse resultado e ausência de novos fatos de revogação.

elapsed inclui criação/fechamento de HttpClient e parsing; no último rótulo inclui polling/espera. Latency/Connect/bytes de JSR223 não foram instrumentados. Dez amostras não validam capacidade, p95, 300 req/s, estabilidade, SLA ou percentual de propagação. Auditoria sob carga fica para 10A2b3; observabilidade/medição para 10B/10C.

Referências: [ADR-048](../../docs/adr/048-carga-de-recurso-e-revogacao.md), [evidências](../../docs/evidencias/etapa-10a2b2.md), [runbook](../../docs/runbooks/carga-recurso-revogacao-local.md), [guia](../../docs/GUIA-DE-IMPLEMENTACAO.md).
