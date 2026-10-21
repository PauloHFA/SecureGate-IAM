# Runbook — Métricas Prometheus do IAM (10B1)

## Preparação e ativação

Java 21, build validado e infraestrutura do [guia local](../../infra/README.md). Inicializar token próprio, sem colocá-lo em .env ou na linha de comando:

```powershell
.\infra\scripts\Initialize-MetricsToken.ps1
.\infra\scripts\Start-IamCore.ps1 -EnablePrometheus -EnableHttpHistograms
```

O segundo comando permanece executando; iniciar os módulos/flags de negócio necessários conforme seus runbooks, na mesma instância IAM. Não iniciar outro IAM na mesma porta. Histogramas são opcionais; omitir EnableHttpHistograms mantém timer/count/sum sem os buckets adicionais. Bootstrap não é necessário para leitura de Prometheus. Scripts passam caminho privado, sem valor do token, ao processo Java. Nenhum serviço Prometheus/Grafana é iniciado aqui.

Initialize-MetricsToken grava somente infra/.local/iam-metrics-token, recusa arquivo existente e não ajusta ACL. Não sobrescrever token em uso para tentar corrigir 401. Para rotação planejada, substituir a credencial privada em manutenção e reiniciar IAM; o digest fica em memória até reinício. Proteger o diretório por permissões locais. O teste do inicializador foi executado em cópia isolada, sem gerar/alterar credencial da stack persistente.

## Consulta manual sem imprimir credencial

Em outro terminal, criar artefato local ignorado:

```powershell
$metricsTokenFile = Join-Path $PWD 'infra/.local/iam-metrics-token'
$metricsToken = [IO.File]::ReadAllText($metricsTokenFile).Trim()
[IO.Directory]::CreateDirectory((Join-Path $PWD 'load-tests/.local')) | Out-Null
try {
  Invoke-WebRequest -UseBasicParsing -Uri http://127.0.0.1:8080/actuator/prometheus -Headers @{ Authorization = 'Bearer ' + $metricsToken } -OutFile load-tests/.local/iam-metrics.prom
} finally {
  $metricsToken = $null
}
```

Rever antes de compartilhar: exporta nomes/estado de operação/rotas da aplicação. Não versionar resultado. Diretório ignorado não substitui ACL. Token de métricas não serve para /actuator/metrics ou APIs administrativas. Não usar o token bootstrap em futuros scrapers.

## Diagnóstico

| Sintoma | Verificação |
| --- | --- |
| Falha de startup com mensagem de token | Conferir arquivo regular, até 512 bytes, 32–256 caracteres após trim, sem espaços internos; não imprimir conteúdo. |
| 401 | Token próprio ausente/incorreto/duplicado ou arquivo trocado sem reinício. Bootstrap não autentica nesse endpoint. |
| 403 com flag desligada | Contrato padrão: endpoint desativado/negado; não é uma falha de scrape aprovado. |
| 403 em POST com token válido | Leitura somente; escrita proibida. |
| 404 após autenticação | Conferir exposição/base path e registro Prometheus se houver override externo; não tornar público como correção. |
| Timer sem buckets | Habilitar EnableHttpHistograms, reiniciar e produzir tráfego. O scrape só observa requisições já concluídas. |
| Família customizada ausente/NaN | Conferir flags/eventos/available/freshness no [inventário](../OBSERVABILIDADE.md); ausência não significa zero. |

A rota é autenticada, mas o modo local usa HTTP loopback. Coleta remota, TLS/rede/credenciais do scraper e limites de tráfego precisam da próxima entrega/revisão de hardening. Não expor essa porta para a rede apenas para facilitar coleta.

## Validação

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=PrometheusSecurityConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=PrometheusIT,IamCoreIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp clean verify
```

PrometheusIT ativa explicitamente observabilidade no contexto de teste, usa token/DB descartáveis e exige JVM, timer/buckets, tag application e template de rota sem query/credenciais. Verifica ausência/incorreção/duplicidade, token administrativo, POST e isolamento de admin/metrics/env. IamCoreIT confere padrão desativado e contrato de saúde/erros. Testes unitários cobrem arquivo inválido/ausente/grande e erro sanitizado. Resultados concretos ficam nas [evidências](../evidencias/etapa-10b1.md).

A coleta/dashboard IAM está na [10B2a](coleta-iam-local.md). Próxima implementação: 10B2b, enforcer/dependências; logs/tracing ficam em 10B3. Nenhum resultado desta entrega é aceite de capacidade/SLA.
