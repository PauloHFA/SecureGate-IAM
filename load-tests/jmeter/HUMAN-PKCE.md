# JMeter humano — 10A2a: massa sintética e PKCE

A 10A2 foi dividida em **10A2a**, massa humana/jornada PKCE/replay, e **10A2b**, falhas de login, validação/revogação de recursos e auditoria. A entrega atual usa JMeter 5.6.3, Java 21 e PowerShell, conforme os [pré-requisitos da base](README.md). Não modifica aplicação ou schema.

## Smoke isolado

Na raiz, com Docker e JMeter instalado, executar no PowerShell Windows:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterHumanSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' verify
```

JMeterHumanSmoke é opt-in e fica fora do build padrão/CI normal. O teste sobe os JARs reais com PostgreSQL/Redis/RabbitMQ/Vault descartáveis e chama os scripts/plano reais. Configura rede 127.0.0.0/24 e sensibilidade PUBLIC de auth:login somente nessa fixture, como perfil de risco sintético sem MFA obrigatório. Não instala provas MFA artificiais nem altera políticas em uma stack persistente.

Gera dois usuários/clientes por API. Com duas threads e uma iteração, espera duas amostras pkce.journey.success e duas pkce.code_replay.expected, sem falhas. Repete com verifier incorreto, exige falha do runner e HTTP 400 nas duas jornadas, e procura senhas, segredos, Basic, e-mails sintéticos e JWT nos artefatos textuais. Confere também sub/client_id do token em relação à linha CSV para detectar troca de identidade entre jornadas.

Artefatos em integration-tests/target/human-pkce-smoke/<execução>/, ignorados. Token administrativo e CSV privado são removidos em finally; encerramento forçado pode deixá-los para limpeza local. O build clean remove os artefatos anteriores do target: preservar apenas relatórios revisados quando necessário. Logs de aplicação da fixture ficam em target/e2e e não devem ser publicados automaticamente.

## Massa humana e ambiente manual

Preferir o smoke isolado. O modo manual cria usuários, clientes e políticas na instância apontada e não os apaga automaticamente. Usar instância dedicada de desenvolvimento com Authorization/Transit e bootstrap habilitados. O gerador não configura rede, sensibilidade de risco ou MFA: esses controles continuam sendo avaliados pelo IAM. Se exigirem MFA, o plano falha em vez de simular uma prova.

Em uma instância dedicada já preparada, iniciar conforme [infraestrutura](../../infra/README.md):

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap -EnableResourceEnforcement
```

Em outro terminal, preparar o perfil sintético e criar a massa. Os dois POSTs de risco abaixo alteram a configuração global dessa instância; usar somente no ambiente dedicado do ensaio, nunca como receita para reduzir controles de um ambiente compartilhado:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$humanSettings = Read-LocalEnvironment (Join-Path $PWD '.env')
[IO.Directory]::CreateDirectory((Join-Path $PWD 'load-tests/.local')) | Out-Null
$humanTokenFile = Join-Path $PWD 'load-tests/.local/admin-human-token'
[IO.File]::WriteAllText($humanTokenFile, $humanSettings.IAM_BOOTSTRAP_TOKEN)
try {
  $humanHeaders = @{ Authorization = 'Bearer ' + $humanSettings.IAM_BOOTSTRAP_TOKEN }
  $null = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/admin/risk/networks -Headers $humanHeaders -ContentType application/json -Body '{"cidr":"127.0.0.0/24"}'
  $null = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/admin/risk/resources -Headers $humanHeaders -ContentType application/json -Body '{"resource":"auth:login","sensitivity":"PUBLIC"}'
  .\load-tests\jmeter\New-HumanMass.ps1 -BaseUri http://127.0.0.1:8080/ -AdminTokenFile $humanTokenFile -OutputFile load-tests/.local/humans.csv -Prefix load-human-manual -Count 2
} finally {
  Remove-Item -LiteralPath $humanTokenFile
  $humanSettings = $null
  $humanHeaders = $null
}
.\load-tests\jmeter\Run-HumanPkce.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -MassFile load-tests/.local/humans.csv -OutputDirectory load-tests/.local/pkce-001
```

Os comandos acima foram escritos para Windows. Adaptar wrapper/caminhos e usar PowerShell 7 em Linux; essa plataforma não foi exercitada nesta entrega.

New-HumanMass.ps1 cria, para cada índice:

1. Usuário com e-mail prefixo-0001@example.test e senha aleatória gerada por CSPRNG.
2. Cliente de navegador confidencial, api:read, audience securegate-api, callback fixo http://127.0.0.1:18090/callback, sem refresh/OIDC.
3. Política ALLOW_LIMITED para oauth:<cliente>/issue, api:read e TTL máximo de 120 s. Não usa recurso curinga nem concede papéis ao usuário.

CSV privado UTF-8: email,password,user_id,client_id,client_secret,scope,redirect_uri. Senha humana é criada localmente; segredo do cliente é emitido pela API. IDs/estrutura são reproduzíveis, segredos e tempos não. Só grava sob load-tests/.local ou integration-tests/target e recusa arquivo existente. Criação parcial pode deixar registros sem CSV final; usar namespace/arquivo novos ou recriar o ambiente descartável. Não há rollback administrativo ou reentrega de segredo. Proteger e remover arquivos de credenciais quando não forem necessários.

## Plano e contratos

| Rótulo | Trabalho realizado | Contrato |
| --- | --- | --- |
| pkce.journey.success | Autorizar → página login → autenticar com CSRF → consentir quando necessário → validar callback/state → trocar código com S256 → conferir identidade/cliente/TTL. | Token Bearer com formato JWT, sub/client_id da massa e validade positiva até 120 s. |
| pkce.code_replay.expected | Repetir a troca do código consumido, com o verifier correto. | HTTP 400, invalid_grant, sem novo token; amostra marcada como sucesso quando a recusa confere. |

human-pkce.jmx executa human-pkce.groovy como JSR223Sampler. Cada jornada usa HttpClient e CookieManager novos, sem redirects automáticos. Segue somente navegação de mesma origem; o callback fixo é validado, mas nunca acessado. Verifier/state são novos por jornada, CSRF é obtido dos formulários reais e o consentimento persistido pode permitir retorno direto de código nas repetições. O código fica em memória somente até a prova de replay. O próximo par nunca herda código/verifier/cookies do anterior, mesmo após falha.

Uma amostra de jornada inclui várias requisições e parsing, não equivale à latência de /oauth2/token. O primeiro fluxo com consentimento faz sete requisições; com consentimento já salvo pode fazer cinco. Cada replay faz mais uma. Duas threads/uma iteração geram quatro amostras agregadas, não quatro requisições HTTP. Campos de rede Latency/Connect/bytes do JSR223 não são instrumentados e podem ficar zero; usar elapsed apenas para duração agregada, sem inferir latência de rede. As claims são decodificadas para conferir o contrato; o plano não valida assinatura criptográfica.

O runner aceita 1–20 threads, 1–100 iterações, ramp-up 1–60 s, exige massa suficiente, alvo loopback e diretório novo. Reutiliza o perfil seguro de gravação da 10A1. Cada rótulo deve ter Threads × Loops amostras sem falha. Gera samples.csv, summary.csv, dashboard/index.html, run.json e jmeter.log. Não grava corpos/headers/variáveis privadas. Não adicionar listeners de corpo ou debug HTTP. A proteção verificada vale para os arquivos/nível de logging versionados.

## Falhas e controle negativo

-WrongVerifier altera o verifier enviado sem mudar o challenge. É injeção de falha: uma execução com essa flag deve falhar, com HTTP 400 na jornada. O replay seguinte também falha com código local 599 por ausência de contexto de sucesso, sem nova chamada HTTP; ele não tenta reutilizar um código de outra iteração. Não agregar essa execução à medição de sucessos.

Se a criação falhar, a mensagem identifica user/browser-client/policy sem resposta privada. Se o plano falhar, conferir summary.csv, status e configuração/políticas do ambiente. O teste não remove bloqueios Redis, não fabrica MFA e não contorna limites do IAM. Histórico de risco, sessões e consentimentos no modo manual podem afetar novas execuções; usar instância limpa quando for necessário comparar resultados.

A 10A2a não mede MFA, falhas de senha, refresh/OIDC, acesso protegido, revogação ou ingestão/consulta de auditoria sob carga. Esses cenários continuam na 10A2b. O smoke não comprova p95, 300 req/s, estabilidade de memória ou SLA. Ver [ADR-046](../../docs/adr/046-carga-humana-pkce.md), [evidências](../../docs/evidencias/etapa-10a2a.md) e [guia](../../docs/GUIA-DE-IMPLEMENTACAO.md).

## Continuação da 10A2b

A [10A2b1 — falhas de login](LOGIN-FAILURES.md) está implementada em plano separado; 10A2b2 continua com recursos/revogação e 10A2b3 com auditoria. O plano PKCE desta página mantém seu escopo e limites históricos.

## Extensão de recurso/revogação — 10A2b2

O mesmo gerador aceita -IncludeAccountPolicy para acrescentar política ALLOW_LIMITED account:self/read (api:read, TTL máximo 120 s). Essa política não é isolada por usuário; usar stack dedicada. Sem o switch, o comportamento deste plano permanece. A jornada compartilhada só retém access_token/expiração em memória quando retain_resource_token=true, usado pelo novo runner; o plano PKCE/replay anterior não solicita essa opção. Ver [RESOURCE-REVOCATION.md](RESOURCE-REVOCATION.md).
