# Evidências — Etapa 10B1: exportação Prometheus protegida

- Data: 21/10/2026; horários UTC−03.
- Base: 42b9707, branch feat/iam-prometheus-metrics; diretório limpo ao iniciar.
- Estado: implementação local, incluída neste commit; push/CI remota pendentes.

## Entrega e divisão

Exportação Prometheus opt-in do IAM, token de leitura próprio em arquivo e histogramas HTTP opcionais. Inventário de meters existentes e próximos passos da observabilidade. Registry gerenciado pelo BOM: micrometer-registry-prometheus **1.15.12**, confirmado dentro do JAR. Endpoint usa cadeia exata, METRICS_READ, GET somente, sem sessão e comparação de digest constante. Não reutiliza token administrativo.

Launcher ganhou EnablePrometheus/EnableHttpHistograms; inicializador CSPRNG cria arquivo privado sem sobrescrita. Não alterou .env, schema (V22 atual), grants SQL, ACL Vault ou stack persistente. Enforcer permanece sem HTTP. 10B2 tratará coleta/dashboards/enforcer/dependências; 10B3 logs/tracing. Nenhum collector/Grafana ou infraestrutura de tracing foi instalado/ativado. A etapa 10 e a 10B ainda não têm aceite global.

## Testes focados

```powershell
.\mvnw.cmd -B -ntp -pl iam-core -am spotless:apply '-Dtest=PrometheusSecurityConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=PrometheusIT,IamCoreIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 00:53:58**, duração **24,903 s**. **14 testes**: dois unitários de arquivo/validação, dez IamCoreIT e dois PrometheusIT, zero falhas/erros/ignorados. JaCoCo desativado nesta seleção. Log ignorado .firecrawl/etapa10b1-focused.log.

No primeiro ensaio, o endpoint não ficou registrado no contexto de teste e retornou 404 após autenticar. PrometheusIT passou a ativar explicitamente observabilidade com AutoConfigureObservability; a seleção repetida foi aprovada com exportador real. Não houve flexibilização de autenticação para corrigir o teste.

| Verificação | Resultado observado |
| --- | --- |
| Flag padrão desligada | Bean do endpoint ausente e rota negada (403); probes/erros existentes aprovados. |
| Token correto | GET 200, formato text/plain, sem Set-Cookie. |
| JVM e HTTP | jvm_memory_used_bytes, http_server_requests_seconds_bucket, application=iam-core e template /api/admin/users/{id} presentes. |
| Tokens ausente/incorreto/admin/duplicado | HTTP 401 na exportação. |
| Escrita | POST com token de leitura recebe 403. |
| Escopo | Mesmo token recebe 401 em admin/metrics JSON e 403 em env. |
| Dados sensíveis | Scrape não contém tokens de leitura/admin, senha do banco, query marker ou caminho do arquivo privado da fixture. |
| Arquivos inválidos | Ausência, vazio, curto, tamanho >512 e whitespace interno recusados com mensagem sanitizada, sem causa/caminho. |

O teste cobre uma rota parametrizada e query sintética; não é prova universal de redação de todos os meters/logs/extensões. Arquivo de teste removido em AfterAll; uma falha abrupta pode deixar arquivo no target ignorado.

## Build completo

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Primeira execução terminou às 00:54:28 antes de executar testes IAM: Surefire relatou ClassNotFoundException para AccessServiceTest existente. O arquivo .class estava presente na inspeção seguinte. Não foi atribuída causa nem aplicada alteração para contornar o erro; nova execução limpa foi iniciada. Log inicial ignorado: .firecrawl/etapa10b1-verify-initial.log.

Nova execução: **BUILD SUCCESS às 01:05:45**, duração Maven **10 min e 19 s**. **782 testes em 74 relatórios XML**, zero falhas/erros/ignorados, incluindo **11 E2E**. Spotless, arquitetura e limites JaCoCo IAM/enforcer aprovados. O ClassNotFoundException inicial não se repetiu nessa execução; não foi declarada uma causa/resolução definitiva. Log ignorado .firecrawl/etapa10b1-verify.log. Os relatórios padrão não incluem suítes Smoke opt-in (zero suítes com Smoke no nome); os 14 testes focados são uma seleção sobreposta, não são somados aos 782. O clean removeu os relatórios focados anteriores, preservando o log da seleção.

O resultado funcional/cobertura é aprovado **com a ressalva de encerramento da JVM descrita abaixo**; não representa aceite de lifecycle sem pendências. O processo externo PowerShell reportou status 1, enquanto o log Maven terminou em BUILD SUCCESS e todos os XML têm zero falhas/erros. Uma prova isolada em PowerShell 5.1 confirmou que stderr de comando nativo com exit code 0 torna $? falso nessa captura; portanto não se declara exit code externo zero. O aviso de encerramento e os relatórios são registrados separadamente.

Ressalva observada nessa nova execução: após os 286 testes de integração do IAM sem falhas, Failsafe informou encerramento forçado da JVM após 30 s de System.exit(0). Dump ignorado em iam-core/target/failsafe-reports/2026-10-21T00-55-28_614-jvmRun1.dump mostra SpringApplicationShutdownHook aguardando ThreadPoolExecutor.awaitTermination em HikariPool.shutdown/HikariDataSource.close. Ocorrência semelhante já foi registrada na 10A2a. Não foi atribuída causa definitiva, não foi aplicada correção de lifecycle nem afirmada ausência desse problema. Verificar esse encerramento em entrega própria; não equivale a falha de asserção ou evidência de correção. O build continuou para enforcer/E2E.

## Scripts e documentação

Parsers dos dois scripts PowerShell aprovados. Inicializador exercitado numa cópia em .firecrawl/etapa10b1-init: gerou 64 hexadecimais CSPRNG, recusou segunda execução sem mudar conteúdo, arquivo privado de teste removido. Nenhum token da stack persistente foi gerado/alterado. O inicializador não configura ACL; diretório ignorado não substitui permissões do sistema.

git diff --check sem erros; 1115 links Markdown locais válidos e os 51 registros de etapas referenciados no guia. Comparação em memória dos 17 arquivos alterados com cinco valores sensíveis locais: zero correspondências, sem imprimir valores.

## Limites e próxima entrega

Endpoint por padrão desligado; registry interno ainda está no classpath. Histogramas ativados aumentam séries/memória e usam faixa esperada 1 ms–10 s, sem mudar timeout/negócio. Credencial por arquivo exige proteção e reinício na rotação; modo local usa HTTP loopback. Queries PromQL no inventário são propostas, não foram executadas contra Prometheus.

Não mede p95 real/capacidade, 300 req/s, estabilidade, métricas de todos os containers, latência distribuída ou tracing HTTP/RabbitMQ. Não há novo formato de logs nesta entrega. Séries customizadas dependem de flags/eventos, e snapshots podem ficar antigos/indisponíveis. Scrape autenticado não substitui revisão de rede/TLS/hardening.

Próxima implementação: **10B2 — coleta e dashboards**, incluindo estratégia do enforcer/dependências; depois **10B3 — logs/tracing**. Publicação/CI e aceite global da etapa 10 continuam pendentes.

Referências: [inventário](../OBSERVABILIDADE.md), [ADR-050](../adr/050-exportacao-prometheus-protegida.md), [runbook](../runbooks/metricas-prometheus-local.md), [guia](../GUIA-DE-IMPLEMENTACAO.md).
