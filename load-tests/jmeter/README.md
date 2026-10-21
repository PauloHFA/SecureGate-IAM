# JMeter — 10A1: Client Credentials e massa sintética

Plano executável de smoke: dois clientes sintéticos, duas threads e duas iterações produzem oito amostras. A 10A está dividida: **10A1** implementa esta base; **10A2** acrescentará massa humana, PKCE, falhas de login, validação/revogação e auditoria. O aceite de carga da etapa 10 depende também de observabilidade/medições posteriores.

## Pré-requisitos

Java 21, Docker Desktop para o smoke isolado, PowerShell 5.1 no Windows ou PowerShell 7 no Linux e Apache JMeter **5.6.3**, baseline validada. Obter a distribuição binária no [arquivo oficial Apache](https://archive.apache.org/dist/jmeter/binaries/), conferir SHA-512 e extrair fora dos arquivos versionados. Não há download automático pelo build. A distribuição usada nesta entrega foi conferida com o arquivo .sha512 do mesmo diretório; isso verifica integridade, não é validação independente de assinatura.

## Smoke isolado, recomendado para desenvolvimento

Na raiz, substituir o caminho da instalação. Os comandos abaixo são do Windows; em Linux, adaptar para ./mvnw, pwsh e caminhos POSIX. Essa plataforma não foi exercitada nesta entrega:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Tools/apache-jmeter-5.6.3' verify
```

JMeterSmoke é opt-in pelo nome explícito; o build normal não executa essa classe nem exige JMeter. O teste usa a fixture descartável de PostgreSQL/Redis/RabbitMQ/Vault e os JARs reais do IAM/enforcer. Cria clientes pela API administrativa, fora da parte cronometrada, e chama os mesmos scripts abaixo. Confirma dashboard, oito amostras, ausência das credenciais de fixture/Basic/JWT nos artefatos textuais e falha do runner ao trocar todas as senhas por valores inválidos.

Os resultados ficam em integration-tests/target/load-smoke/<execução>/, ignorado pelo Git. O arquivo administrativo e os CSVs privados são removidos em finally; um encerramento forçado da JVM pode deixá-los no diretório ignorado e exige limpeza local. Logs da aplicação ficam no target/e2e da fixture; não publicá-los sem revisão.

## Uso contra um IAM local preparado

Este modo cria dados no ambiente apontado. Preferir uma instância dedicada de teste com Authorization/Transit e administração bootstrap habilitados, conforme [operação OAuth](../../infra/README.md). Não usar dados reais ou produção. Os scripts aceitam apenas localhost/127.0.0.1, sem caminho, query, fragmento ou credenciais na URI.

Com a infraestrutura local preparada, o IAM pode ser iniciado em um terminal separado:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap
```

Em outro terminal, criar arquivo de token a partir da configuração local, sem imprimir seu conteúdo:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$loadSettings = Read-LocalEnvironment (Join-Path $PWD '.env')
[IO.Directory]::CreateDirectory((Join-Path $PWD 'load-tests/.local')) | Out-Null
$loadTokenFile = Join-Path $PWD 'load-tests/.local/admin-token'
[IO.File]::WriteAllText($loadTokenFile, $loadSettings.IAM_BOOTSTRAP_TOKEN)
try {
  .\load-tests\jmeter\New-ClientMass.ps1 -BaseUri http://127.0.0.1:8080/ -AdminTokenFile $loadTokenFile -OutputFile load-tests/.local/clients.csv -Prefix load-manual -Count 2
} finally {
  Remove-Item -LiteralPath $loadTokenFile
  $loadSettings = $null
}
.\load-tests\jmeter\Run-ClientCredentials.ps1 -JMeterHome C:/Tools/apache-jmeter-5.6.3 -BaseUri http://127.0.0.1:8080/ -MassFile load-tests/.local/clients.csv -OutputDirectory load-tests/.local/run-001 -Threads 2 -Loops 2 -RampUpSeconds 1
```

O gerador usa IDs prefixo-0001, prefixo-0002 etc., escopo load:read e audience load-test. O segredo é emitido pela API, não é fixo nem derivado de seed. A estrutura da massa é reproduzível; segredos e tempos variam. CSV UTF-8 tem cabeçalho client_id,client_secret,scope; não versionar exemplos com segredos funcionais. A escrita é permitida somente sob load-tests/.local ou integration-tests/target, e arquivo existente é recusado.

Criação parcial não é desfeita: se houver falha/409, clientes anteriores podem existir e o arquivo final não será entregue. Não tentar recuperar segredo por GET. Usar novo namespace e arquivo ou recriar o ambiente descartável. O teste isolado remove o banco ao encerrar; o modo manual não remove clientes automaticamente. Proteger os diretórios privados por permissões do sistema operacional e remover CSVs quando não forem necessários.

## Contratos e interpretação

| Rótulo | Contrato | Como interpretar |
| --- | --- | --- |
| token.success | HTTP 200, Bearer, access_token com formato JWT e expires_in positivo | Sucesso de emissão; não verifica criptograficamente o JWT neste plano. |
| token.invalid_client.expected | HTTP 401, invalid_client e ausência de access_token | Rejeição esperada é marcada como amostra bem-sucedida quando o contrato confere. |

Cada iteração realiza as duas requisições, sem think time. Headers/corpos são limpos após asserção e não gravados pelo perfil safe-results.properties. Evitar debug HTTP, listeners de corpo e variáveis extras; a proteção verificada corresponde ao runner e plano versionados. Há timeout de conexão 2 s e resposta 5 s, sem seguir redirects. O runner limita 1–20 threads, 1–100 iterações, 1–60 s de ramp-up e exige pelo menos tantos clientes quanto threads. A distribuição do CSV é compartilhada e reciclada entre threads; não vincula um cliente permanentemente a uma thread.

O runner recusa diretório de execução existente e gera:

- samples.csv: amostras sem corpo, tokens ou cabeçalhos.
- summary.csv: contagem/falhas por rótulo; qualquer falha ou contagem incompleta faz o script falhar, mesmo se o processo JMeter retornar zero.
- dashboard/index.html: relatório HTML por rótulo, inclusive a negativa esperada.
- run.json: parâmetros, heap 256m, sistema e CPUs lógicas; baseline JMeter validada identificada separadamente da instalação fornecida.
- jmeter.log: log local; não habilitar debug com credenciais reais.

O agregado mistura emissão e negativa: não o usar como latência de emissão. Ler token.success separadamente. Oito amostras não validam p95, 300 req/s, consumo estável, HA ou SLA. Threads são concorrência, não uma taxa de requisições. Warm-up, patamar/ramp-down, coleta de recursos e ensaios de capacidade ficam para as próximas entregas.

Ver [ADR-045](../../docs/adr/045-base-de-carga-jmeter.md), [evidências 10A1](../../docs/evidencias/etapa-10a1.md) e [guia passo a passo](../../docs/GUIA-DE-IMPLEMENTACAO.md).

Referências técnicas: [execução CLI e relatório](https://jmeter.apache.org/usermanual/get-started.html), [persistência de amostras e propriedades](https://jmeter.apache.org/usermanual/listeners.html). Somente o comportamento efetivamente exercitado é declarado validado.
