# Recursos das dependências durante carga — 10C2b2a

A 10C2b2a acrescenta amostragem de containers ao [runner JMeter](../../load-tests/jmeter/Run-TokenIssuanceBaseline.ps1), para correlacionar carga de emissão ou introspecção com CPU e memória de PostgreSQL, Redis, RabbitMQ e Vault. A 10C2b2b1 reutiliza a coleta no cenário de recurso protegido. Os quatro IDs são passados por `-DockerContainerIds`, separados por vírgula, na ordem indicada. O runner aceita somente 1–8 IDs Docker completos e distintos e não inicia sem a CLI Docker quando essa opção é usada.

Durante cada fase, `docker stats --no-stream` coleta snapshots aproximadamente a cada 2 s. `container-resources.csv` registra timestamp UTC, ID abreviado, porcentagem de CPU, uso/limite de memória e quantidade de processos; não grava nomes, variáveis, comandos nem segredos dos containers. `summary.json` registra quantidade de pontos, maior CPU% e maior memória MiB por ID. Os valores são snapshots, sujeitos à latência da CLI e à precisão/unidade relatada pelo Docker; 100% de CPU equivale aproximadamente a um núcleo e pode ser ultrapassado.

## Verificação descartável

Com Docker Desktop, Java 21, PowerShell e JMeter 5.6.3 instalado fora do Git, executar na raiz:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' '-Dsecuregate.load.steady.seconds=60' '-Dsecuregate.load.window.seconds=10' verify
```

O teste inicia containers descartáveis, passa seus IDs reais ao runner, confere quatro séries de recursos por fase e examina artefatos contra vazamento de credenciais e JWT. Ele não altera a stack persistente.

## Execução manual em ambiente local dedicado

Criar massa sintética privada conforme o [guia JMeter](../../load-tests/jmeter/README.md). Obter os IDs completos de PostgreSQL, Redis, RabbitMQ e Vault com `docker ps --no-trunc`; conferir manualmente a correspondência antes do ensaio. Exemplo com placeholders:

```powershell
.\load-tests\jmeter\Run-TokenIssuanceBaseline.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -MassFile load-tests/.local/clients.csv -OutputDirectory load-tests/.local/token-with-resources-001 -Threads 2 -WarmupSeconds 10 -SteadySeconds 120 -WindowSeconds 10 -CooldownSeconds 10 -DockerContainerIds '<id-postgres>,<id-redis>,<id-rabbitmq>,<id-vault>'
```

Informar `-IamProcessId` para incluir CPU/memória IAM, se disponível. O Docker pode mostrar recursos de containers mesmo quando a aplicação está parada, por isso comparar timestamps com as fases e registrar capacidade/limites do host. O teste de 60 s e duas threads valida a coleta, não capacidade máxima, estabilidade prolongada nem a meta de 300 req/s. O enforcer não recebe tráfego nesse cenário. Validação no recurso, revogação e auditoria seguem na 10C2b2b.
