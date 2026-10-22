# Medição local da validação no recurso — 10C2b2b1

Este cenário mede somente `GET /api/resources/account` com Bearer humano válido. Cada thread executa uma jornada PKCE real uma vez no início de cada fase; o JMeter registra essa preparação com o rótulo `pkce.journey.success`, mas o resumo e as janelas usam apenas `resource.validation.success`. O token e os cookies ficam na memória do JMeter. O GET usa seu próprio cliente HTTP e somente o cabeçalho Bearer; não recebe cookies do navegador PKCE. O contrato exige HTTP 200, `sub` do usuário preparado e token ainda válido.

Usar apenas stack local dedicada ou a fixture descartável. A massa deve vir de `New-HumanMass.ps1 -IncludeAccountPolicy`, que cria usuários, clientes e políticas reais; a política `account:self/read` afeta todos os sujeitos elegíveis da instância de teste. Preservar CSV, token administrativo e diretório de saída sob `load-tests/.local` ou `integration-tests/target`, ignorados pelo Git. Não copiar artefatos sem conferir seu conteúdo.

## Verificação descartável

Com Docker Desktop, Java 21, PowerShell e JMeter 5.6.3, executar na raiz, ajustando somente o caminho do JMeter:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' '-Dsecuregate.load.scenario=resource' verify
```

Para um patamar de 60 s, acrescentar `'-Dsecuregate.load.steady.seconds=60' '-Dsecuregate.load.window.seconds=10'`. O teste configura risco e política apenas nos containers descartáveis, confere uma preparação por thread em cada fase, amostras do recurso, janelas, recursos IAM/dependências e ausência de credenciais/JWT nos resultados.

## Runner manual

Após preparar massa humana conforme [recurso e revogação](../../load-tests/jmeter/RESOURCE-REVOCATION.md), executar em diretório novo:

```powershell
.\load-tests\jmeter\Run-TokenIssuanceBaseline.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -MassFile load-tests/.local/resources.csv -OutputDirectory load-tests/.local/resource-baseline-001 -Scenario resource -Threads 2 -WarmupSeconds 10 -SteadySeconds 60 -WindowSeconds 10 -CooldownSeconds 10
```

`-IamProcessId` e `-DockerContainerIds` acrescentam métricas de processo e containers; consultar [coleta das dependências](medicao-recursos-dependencias-carga-local.md). Cada fase tem limite de 90 s devido ao TTL de até 120 s do token humano. Se o login, consentimento ou recurso falhar, a fase é recusada; não contar a negativa como latência de validação bem-sucedida.

O CSV `samples.csv` inclui os rótulos de preparação e do GET para auditoria. `summary.json` registra separadamente `setupSamples`; `requests`, falhas, percentis e janelas referem-se somente ao GET. A taxa divide os GETs pela duração configurada da fase, que **inclui o tempo inicial de preparação**; não representa uma taxa ofertada independente. O p95 do GET inclui rede, processamento do recurso, consulta de autorização e cliente JMeter, portanto não isola o custo da validação criptográfica/cache nem prova a referência de validação local < 20 ms. Duas threads e até 60 s verificam o método, não capacidade, estabilidade prolongada ou saturação. Revogação e auditoria seguem nas próximas divisões.
