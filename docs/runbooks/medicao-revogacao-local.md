# Medição local da revogação e propagação — 10C2b2b2

Este cenário mede a chamada de revogação (`POST /oauth2/revoke`) e o tempo de propagação observável até o recurso protegido (`GET /api/resources/account` retornando 401). Cada iteração executa uma jornada PKCE real para obter um token humano com credenciais de cliente confidencial; o JMeter registra essa preparação com o rótulo `pkce.journey.success`. Em seguida, o cliente executa a revogação (`token.revoke.call`) e mede a latência da chamada HTTP. Logo após, a recusa é observada via polling em `revocation.observed_refusal`, garantindo que o token não expirou naturalmente antes de ser recusado.

Usar apenas stack local dedicada ou a fixture descartável. A massa deve vir de `New-HumanMass.ps1 -IncludeAccountPolicy`, que cria usuários, clientes e políticas reais. Preservar CSV, token administrativo e diretório de saída sob `load-tests/.local` ou `integration-tests/target`, ignorados pelo Git.

## Verificação descartável

Com Docker Desktop, Java 21, PowerShell e JMeter 5.6.3, executar na raiz, ajustando somente o caminho do JMeter:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' '-Dsecuregate.load.scenario=revocation' verify
```

Para um patamar de 60 s, acrescentar `'-Dsecuregate.load.steady.seconds=60' '-Dsecuregate.load.window.seconds=10'`. O teste configura risco e política nos containers descartáveis, confere a preparação por iteração, amostras de chamada de revogação e de propagação, recursos IAM/dependências e ausência de credenciais/JWT nos resultados.

## Runner manual

Após preparar massa humana conforme [recurso e revogação](../../load-tests/jmeter/RESOURCE-REVOCATION.md), executar em diretório novo:

```powershell
.\load-tests\jmeter\Run-TokenIssuanceBaseline.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -MassFile load-tests/.local/resources.csv -OutputDirectory load-tests/.local/revocation-baseline-001 -Scenario revocation -Threads 2 -WarmupSeconds 10 -SteadySeconds 60 -WindowSeconds 10 -CooldownSeconds 10
```

`-IamProcessId` e `-DockerContainerIds` acrescentam métricas de processo e containers; consultar [coleta das dependências](medicao-recursos-dependencias-carga-local.md). Cada fase tem limite de 90 s devido ao TTL de até 120 s do token humano.

O arquivo `samples.csv` inclui as amostras de preparação `pkce.journey.success`, a chamada de revogação `token.revoke.call` e a observação da recusa `revocation.observed_refusal`. O `summary.json` registra separadamente `setupSamples`, percentis e taxa de `token.revoke.call`, bem como o bloco `propagation` com as observações e percentis (p50, p95, p99) de propagação. Compara explicitamente `steadyPropagationP95Ms` com o limite de referência de 2000 ms (`targetPropagationMs: 2000`).
