# JMeter — 10A2b1: falhas de login

A 10A2b foi dividida em **10A2b1**, credenciais recusadas, **10A2b2**, acesso/revogação de recursos, e **10A2b3**, auditoria. Esta entrega cobre senha incorreta e usuário inexistente; não conclui a 10A nem mede capacidade.

## Smoke isolado

Usar Java 21, Docker, PowerShell e JMeter 5.6.3, conforme a [base de carga](README.md). Na raiz, em PowerShell Windows:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterLoginFailureSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' verify
```

O teste opt-in sobe JARs reais e serviços descartáveis. Cria um usuário pela API, confirma fora da janela cronometrada que o e-mail ausente não existe no banco e executa uma thread/uma iteração: uma amostra de senha incorreta e uma de usuário desconhecido. Confere dois novos fatos auth.login.failed na Outbox; isso não é teste de ingestão/consulta central sob carga.

A fixture configura rede/sensibilidade sintéticas apenas no ambiente descartável para tornar o controle positivo reprodutível. O gerador não altera risco, MFA, políticas ou projeções Redis. O teste repete com senha correta e exige que o runner detecte uma sessão autenticada, em vez de aceitar o fluxo como falha de credenciais. Procura token administrativo, senha, identificadores/e-mails sintéticos e JWT nos artefatos textuais.

Artefatos em integration-tests/target/login-failure-smoke/<execução>/, ignorados. Token/CSV privado são removidos em finally; encerramento forçado pode deixá-los para limpeza local. O build clean remove os artefatos anteriores do target. Logs de aplicação da fixture ficam em target/e2e e exigem revisão antes de compartilhar. JMeterLoginFailureSmoke fica fora da suíte padrão/CI normal.

## Preparar massa e executar manualmente

Preferir o smoke isolado. Usar uma instância dedicada de teste com Authorization e bootstrap habilitados; para conferir os controles distribuídos existentes, habilitar também ResourceEnforcement, conforme [infraestrutura](../../infra/README.md):

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap -EnableResourceEnforcement -EnableOutboxPublisher
```

Para manter a propagação dos fatos de falha ativa, iniciar também o consumidor em terminal separado; a fixture do smoke já ativa publicação/consumo:

```powershell
.\infra\scripts\Start-PolicyEnforcer.ps1 -EnableConsumer
```

Em outro terminal para a massa/runner:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$loginSettings = Read-LocalEnvironment (Join-Path $PWD '.env')
[IO.Directory]::CreateDirectory((Join-Path $PWD 'load-tests/.local')) | Out-Null
$loginTokenFile = Join-Path $PWD 'load-tests/.local/admin-login-token'
[IO.File]::WriteAllText($loginTokenFile, $loginSettings.IAM_BOOTSTRAP_TOKEN)
try {
  .\load-tests\jmeter\New-LoginFailureMass.ps1 -BaseUri http://127.0.0.1:8080/ -AdminTokenFile $loginTokenFile -OutputFile load-tests/.local/login-failures.csv -Prefix load-login-manual -Count 1
} finally {
  Remove-Item -LiteralPath $loginTokenFile
  $loginSettings = $null
}
.\load-tests\jmeter\Run-LoginFailures.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -MassFile load-tests/.local/login-failures.csv -OutputDirectory load-tests/.local/login-001
```

Comandos exercitados em Windows. Em Linux adaptar para ./mvnw, PowerShell 7 e caminhos POSIX; essa plataforma não foi exercitada nesta entrega.

New-LoginFailureMass.ps1 cria somente usuários, com IDs/e-mails prefixados e senhas CSPRNG. Não precisa de clientes OAuth ou políticas de emissão. CSV UTF-8 privado: email,password,user_id,unknown_email. O e-mail ausente usa absent-<UUID aleatório>@example.test e não é cadastrado; o smoke confirma sua ausência com SQL fora do plano. O modo manual exige ambiente limpo/inventário confiável, não tenta descobrir existência pela interface pública.

Token administrativo é lido por arquivo e nunca enviado ao plano. CSV só é escrito sob load-tests/.local ou integration-tests/target. O gerador recusa arquivo existente; criação parcial pode deixar usuários sem CSV final. Usar namespace/arquivo novos ou recriar a instância de teste. Não há exclusão/rollback automático dos usuários no modo manual. Proteger e remover credenciais locais quando não forem necessárias.

## Contratos do plano

| Rótulo | Credenciais e sequência | Aprovação |
| --- | --- | --- |
| login.invalid_password.expected | Usuário conhecido, senha alterada; GET login → POST com CSRF → página de erro → GET protegido /mfa. | Redirect 302 para /login?error, mensagem genérica sem dados da massa e navegador anônimo. |
| login.unknown_user.expected | E-mail ausente, senha incorreta; mesmo fluxo com cookies novos. | Mesmo redirect e mensagem do caso conhecido; navegador anônimo. |

login-failures.jmx executa login-failures.groovy como JSR223Sampler. Cada tentativa usa HttpClient/CookieManager novos, sem redirects automáticos; navegação só na mesma origem. CSRF é extraído do formulário real. A mensagem do alerta é comparada entre os dois casos da mesma thread; os HTMLs completos variam por CSRF/cookies e não são comparados byte a byte. Nenhum código tenta autenticar ou resolver o usuário desconhecido por endpoint administrativo durante a medição.

Uma amostra aprovada agrega quatro chamadas HTTP e parsing; duas amostras padrão correspondem a oito chamadas, não duas. elapsed não é latência isolada de POST /login. Latency/Connect/bytes não são instrumentados nesse sampler. A comparação não mede nem comprova resistência a enumeração por tempo de resposta; cobre status/interface e ausência de identificadores no erro.

O runner suporta 1–20 threads, 1–100 iterações, ramp-up 1–60 s e massa suficiente para as threads, mas o padrão validado é **uma thread e uma iteração**. Repetição/concorrência elevadas podem ativar controles reais: HTTP 429, 403 ou 503 falham o contrato, nunca são reclassificados como senha inválida. Não limpar bloqueios Redis, aumentar limites ou enfraquecer políticas para fazer esse plano passar. Usar ambiente novo ou aguardar os prazos documentados antes de repetir.

Reutiliza safe-results.properties, grava samples.csv, summary.csv, dashboard/index.html, run.json e jmeter.log, com corpo/headers redigidos. O runner exige Threads × Loops amostras sem falha por rótulo. Não adicionar listeners de corpo, variáveis privadas ou logs HTTP de debug. Nenhuma credencial ou mensagem de alerta é gravada nos resultados.

## Controle de detecção de erro

-UseCorrectPassword é injeção de falha, não um cenário aprovado. O caso conhecido envia a senha real. Se a sessão autenticar, o plano confirma a página autenticada e retorna falha com mensagem estática unexpected_authentication. O caso desconhecido seguinte falha localmente com código 599, sem nova chamada, por faltar a referência de recusa válida. O runner deve terminar com erro. Essa execução pode criar sessão autenticada no servidor; usar apenas na fixture descartável ou instância dedicada.

Se o caso conhecido falhar por qualquer outro motivo, o plano também descarta a mensagem anterior; não compara o caso seguinte com estado antigo. Relatórios não incluem cookies, CSRF, senhas ou dados da massa. Revisar artefatos antes de publicar; a verificação cobre a configuração versionada, não debug/extensões externos.

Ainda não cobre comportamento de bloqueio sob carga, MFA, tempo constante, recuperação de conta, acesso protegido autenticado, revogação ou ingestão/consulta central da auditoria sob carga. As próximas entregas são 10A2b2 e 10A2b3; observabilidade/medição ficam em 10B–10D. Ver [ADR-047](../../docs/adr/047-carga-de-falhas-de-login.md), [evidências](../../docs/evidencias/etapa-10a2b1.md) e [guia](../../docs/GUIA-DE-IMPLEMENTACAO.md).
