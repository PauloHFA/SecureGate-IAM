# Medição local de auditoria centralizada — 10C2b2b3

Este cenário mede o pipeline de auditoria centralizada dividindo de forma estrita três unidades independentes:
1. **Produção (`audit.produce.call`):** chamadas HTTP `POST /api/admin/users` com token administrativo Bearer e identificador de correlação (`X-Correlation-Id`). Cada iteração cria usuários e insere eventos na tabela de outbox transacional com `schemaVersion = 1` e `producer = iam-core`.
2. **Ingestão e visibilidade centralizada (`audit.visibility.observed`):** polling na API de consulta `GET /api/admin/audit/events` com `limit=1` filtrado por correlação até que o evento ingerido pelo consumidor RabbitMQ se torne visível no log permanente com `nextCursor`. Mede o atraso fim a fim entre publicação assíncrona e disponibilidade na consulta.
3. **Consulta paginada por cursor (`audit.cursor.query`):** requisição `GET /api/admin/audit/events` passando o `cursor` retornado na página anterior, validando a integridade da paginação, ordem decrescente temporal e ausência de cursores órfãos.

Usar apenas ambiente local dedicado ou a fixture descartável. Preservar token administrativo, credenciais e diretório de saída sob `load-tests/.local` ou `integration-tests/target`, que são ignorados pelo Git.

## Verificação descartável

Com Docker Desktop, Java 21, PowerShell e JMeter 5.6.3, executar na raiz, ajustando somente o caminho do JMeter:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' '-Dsecuregate.load.scenario=audit' verify
```

O teste habilita a jornada de auditoria no container PostgreSQL (executa privilégios restritos de ingestão, configura partições e inicia o consumidor RabbitMQ em `iam-core`), executa as três fases (aquecimento, regime permanente e desaceleração), verifica ausência de falhas em produção, visibilidade e cursor, coleta métricas do processo IAM e dos containers de dependência, e certifica que nenhum segredo ou JWT foi persistido nos relatórios.

## Runner manual

Após obter o token administrativo em arquivo (`admin-token.txt`), executar em diretório novo:

```powershell
.\load-tests\jmeter\Run-TokenIssuanceBaseline.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -AdminTokenFile load-tests/.local/admin-token.txt -OutputDirectory load-tests/.local/audit-baseline-001 -Scenario audit -Threads 2 -WarmupSeconds 10 -SteadySeconds 60 -WindowSeconds 10 -CooldownSeconds 10
```

`-IamProcessId` e `-DockerContainerIds` acrescentam telemetria de processo e containers; consultar [coleta das dependências](medicao-recursos-dependencias-carga-local.md).

O arquivo `samples.csv` inclui as amostras de produção `audit.produce.call`, visibilidade `audit.visibility.observed` e consulta paginada `audit.cursor.query`. O `summary.json` registra separadamente os percentis de produção (p50, p95, p99), janelas móveis de regime permanente, bem como os blocos dedicados `visibility` e `cursorQuery` com contagem, falhas e percentis, comparando a visibilidade contra o limite máximo de referência de 5000 ms (`targetPropagationMs: 5000`).
