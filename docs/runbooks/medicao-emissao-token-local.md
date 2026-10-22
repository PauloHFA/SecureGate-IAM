# Medição local de emissão de token — 10C1/10C2a

A 10C1 cria uma linha de base reproduzível para `/oauth2/token` com `client_credentials`. O plano [token-issuance-baseline.jmx](../../load-tests/jmeter/token-issuance-baseline.jmx) executa somente `token.success`; recusas esperadas não entram na taxa nem nos percentis de emissão. Ele usa três processos JMeter sequenciais: aquecimento, patamar e redução de concorrência. Essa separação introduz intervalo entre fases; não representa um único ramp-up contínuo.

## Ensaio descartável de verificação

Com Docker Desktop, Java 21, PowerShell e JMeter 5.6.3 instalado fora do Git, executar na raiz:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' verify
```

O teste gera dois clientes pela API administrativa de uma fixture descartável e executa 3 s / 5 s / 3 s com 1 / 2 / 1 threads. Exige contrato OAuth válido, amostras e recursos IAM em cada fase, três objetos no relatório e ausência dos segredos/Basic/JWT nos artefatos. Esse perfil curto verifica o runner; não constitui benchmark de capacidade.

## Execução manual em ambiente local dedicado

Preparar IAM de teste e CSV privado conforme [guia JMeter](../../load-tests/jmeter/README.md). O runner recusa diretório de saída existente e URI fora de loopback. Exemplo inicial, com dados sintéticos:

```powershell
.\load-tests\jmeter\Run-TokenIssuanceBaseline.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -MassFile load-tests/.local/clients.csv -OutputDirectory load-tests/.local/token-baseline-001 -Threads 2 -WarmupSeconds 30 -SteadySeconds 120 -CooldownSeconds 30
```

`-IamProcessId` é opcional. Ao fornecê-lo, o runner amostra CPU cumulativa, working set e memória privada do processo IAM a cada segundo; não registra linha de comando nem ambiente. Para comparar perfis, registrar também limites de CPU/memória do Docker, número de threads, versões, máquina e interferência de outros processos. O runner registra capacidade do daemon Docker quando disponível.

`averageCpuCorePercent` usa segundos de CPU divididos pelo tempo decorrido: 100% equivale a um núcleo ocupado e o valor pode superar 100% em processos paralelos. O pico de memória é apenas o processo IAM, não o consumo total da stack.

O arquivo `summary.json` guarda contagem, falhas, taxa efetiva (`requisições / duração configurada`), p50/p95/p99 por fase e indicadores separados para as referências de 200 ms e 300 req/s. `samples.csv`, `jmeter.log`, `console.log` e `iam-resources.csv` ficam por fase sob o diretório privado ignorado pelo Git. Nenhum token, corpo ou cabeçalho deve ser enviado a logs externos; revisar os artefatos antes de compartilhá-los.

Na 10C2a, `-WindowSeconds` (padrão 10 s) acrescenta `windows` apenas ao patamar. Cada janela registra índice, deslocamento desde a primeira amostra, duração nominal, emissões, falhas, taxa e p95. Só entram janelas cujo fim não é posterior ao timestamp da última amostra observada; amostras da cauda e a janela parcial ficam apenas no agregado. Esse método usa o timestamp de início da amostra JMeter como relógio e pode excluir uma janela completa se o tráfego parar antes da borda. Janelas sem amostras têm taxa zero e p95 nulo. Comparar janelas para detectar queda de taxa ou aumento de latência, sem usar a média para esconder degradação.

Para uma seleção descartável de 1 minuto, acrescentar `'-Dsecuregate.load.steady.seconds=60' '-Dsecuregate.load.window.seconds=10'` ao comando Maven acima. O teste continua com duas threads no patamar e verifica as janelas, não demonstra 300 req/s nem mede a oferta independente. Em ambiente dedicado, escolher `-SteadySeconds 120 -WindowSeconds 10` ou duração maior, registrar a concorrência e repetir perfis. O runner rejeita largura de janela maior que o patamar.

Uma execução válida exige zero falhas do contrato e amostras em cada fase. Referências de desempenho não são critérios de aprovação do runner. Para declarar capacidade, repetir patamares mais longos com recursos de todos os componentes, taxa ofertada/efetiva, distribuição de erros e degradação ao longo do tempo. A 10C2b continua responsável por isso e pelos demais fluxos de validação, revogação e auditoria.
