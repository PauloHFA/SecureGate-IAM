# Medição local de introspecção de token — 10C2b1

A 10C2b1 mede `POST /oauth2/introspect` de um access token ativo pertencente ao cliente autenticado por Basic. O plano [JMeter](../../load-tests/jmeter/token-introspection-baseline.jmx) tem somente o rótulo `introspection.active`: exige HTTP 200, `active: true` e `client_id` do proprietário. Não mistura emissão, tokens inválidos nem revogação na latência. O endpoint HTTP consultado não é a validação local com cache do recurso; o p95 de 20 ms do plano não é critério de aprovação deste cenário.

O [runner](../../load-tests/jmeter/Run-TokenIssuanceBaseline.ps1) usa `-Scenario introspection` e reutiliza as três fases e janelas de 10C2a. Antes de cada fase, emite um token novo por cliente sintético. Exige validade maior que a fase mais 15 s; o token de 5 min da configuração atual limita a duração contínua de cada fase. A preparação fica fora das amostras JMeter. O CSV temporário contém senhas e JWTs, é criado somente sob `load-tests/.local` ou `integration-tests/target`, e é removido ao terminar cada fase. Proteger esse diretório durante o ensaio; uma interrupção forçada pode impedir a remoção.

## Verificação descartável

Com Docker Desktop, Java 21, PowerShell e JMeter 5.6.3 instalado fora do Git, executar na raiz:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' '-Dsecuregate.load.scenario=introspection' '-Dsecuregate.load.steady.seconds=60' '-Dsecuregate.load.window.seconds=10' verify
```

O teste cria dois clientes e infraestrutura descartável, usa 3/60/3 s em três processos JMeter e verifica contrato, janelas, recursos IAM, remoção do CSV temporário e ausência de segredos/Basic/JWT dos artefatos persistidos. Ele não utiliza o `.env` nem altera a stack persistente.

## Execução manual em ambiente local dedicado

Preparar IAM de teste e CSV sintético privado conforme o [guia JMeter](../../load-tests/jmeter/README.md). Escolher um diretório de saída novo e ignorado pelo Git:

```powershell
.\load-tests\jmeter\Run-TokenIssuanceBaseline.ps1 -Scenario introspection -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -MassFile load-tests/.local/clients.csv -OutputDirectory load-tests/.local/introspection-001 -Threads 2 -WarmupSeconds 10 -SteadySeconds 120 -WindowSeconds 10 -CooldownSeconds 10
```

Ler `summary.json` e as janelas do patamar separadamente. Taxa = introspecções concluídas/duração configurada; a concorrência fechada não controla taxa ofertada. `-IamProcessId` permite amostrar apenas o processo IAM. A ausência de falhas é condição para aceitar a execução funcional, mas as referências de desempenho requerem carga controlada, recursos de todas as dependências, patamares mais longos e repetição. Revogação, validação no recurso e auditoria ficam para as próximas divisões.

A 10C2b2a permite `-DockerContainerIds` para coletar snapshots de PostgreSQL, Redis, RabbitMQ e Vault no mesmo ensaio; ver o [procedimento de recursos](medicao-recursos-dependencias-carga-local.md). Essa coleta não transforma a introspecção HTTP em validação local com cache.
