# Evidências — Etapa 10A2a: massa humana e PKCE/replay

- Data: 20/10/2026; horários UTC−03.
- Base: 1e7a201 (10A1); branch feat/load-human-pkce-smoke.
- Estado: implementação local, incluída neste commit; push/CI remota pendentes.

## Entrega e divisão

Gerador humano por API com senha CSPRNG, cliente confidencial e política ALLOW_LIMITED api:read/TTL 120 s; plano JMeter JSR223/Groovy com cookies/CSRF/state/S256/consentimento; runner com rótulos, dashboard, contagens e injeção WrongVerifier. Teste opt-in com isolamento por identidade e verificação de artefatos. Não há alteração de aplicação, schema, ACL ou stack persistente. V22 permanece atual.

A 10A2 foi dividida: 10A2a cobre massa/PKCE/replay; 10A2b continua responsável por falhas de login, validação/revogação de recursos e auditoria. A etapa 10 não está concluída.

## Ambiente e comandos executados

Java 21, PowerShell Windows, Docker Desktop 29.9.1 e baseline JMeter 5.6.3 já instalada/conferida na 10A1. Mesmas imagens descartáveis: PostgreSQL 16.15-alpine, Redis 7.4.11-alpine, RabbitMQ 4.3.6-management-alpine e Vault 1.21.4. O teste configura rede/sensibilidade de risco sintéticas apenas na fixture; o gerador não modifica risco/MFA.

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterHumanSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterHumanSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify
.\mvnw.cmd -B -ntp clean verify
```

Validações iniciais corrigiram o nome de política para a gramática existente e o uso de restrições/TTL para ALLOW_LIMITED. O roteiro também passou a aceitar consentimento salvo, necessário para repetir jornadas sem presumir uma nova página de consentimento. Nenhuma regra do IAM foi relaxada para passar o teste.

## Smoke opt-in final

**BUILD SUCCESS às 23:18:57**, duração Maven **49,150 s**, **1 teste**, zero falhas/erros/ignorados. JaCoCo desativado nessa seleção focada; gates pertencem ao build padrão.

| Execução | pkce.journey.success | pkce.code_replay.expected | Resultado |
| --- | --- | --- | --- |
| Duas threads, uma iteração, massa real criada por API | 2 amostras, 0 falhas, HTTP 200 | 2 amostras, 0 falhas, HTTP 400/invalid_grant esperado | Runner aprovado; dashboard HTML presente. |
| Mesma massa com WrongVerifier | 2 amostras, 2 falhas, HTTP 400 real | 2 amostras, 2 falhas, código local 599 sem requisição | Runner recusado, conforme exigido pelo teste. |

O código 599 é uma falha local por ausência de contexto após a jornada recusada, não uma resposta do IAM. Não se reutiliza código/contexto anterior após falha. O controle negativo faz parte da validação e não é uma falha operacional não resolvida.

Claims sub/client_id conferidas com a massa para cada jornada; verificação de JWT nesse plano é de formato/claims, sem validar assinatura. Busca de senhas, segredo de cliente, Basic, e-mail sintético e JWT nos artefatos textuais não encontrou correspondências. Token/CSV privados removidos em finally. Resultados revisados foram copiados para .firecrawl/etapa10a2a-artifacts antes do clean; log em .firecrawl/etapa10a2a-smoke.log. Tudo ignorado, sem publicação automática.

## Build padrão e revisão documental

Build padrão **BUILD SUCCESS às 23:30:33**, duração Maven **10 min e 44 s**: **778 testes em 72 relatórios XML**, zero falhas/erros/ignorados nos resultados JUnit, incluindo os **11 E2E** anteriores. Arquitetura, Spotless e limites JaCoCo IAM/enforcer aprovados. A lista de XML não contém suítes JMeter: o teste humano foi executado separadamente e não é somado aos 778. O clean removeu seus XML/artefatos anteriores do target; log e cópia sanitizada local preservam o resultado. Log padrão ignorado .firecrawl/etapa10a2a-verify.log.

**Ocorrência de teardown:** apesar do BUILD SUCCESS/status 0, o Failsafe registrou `Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds after System.exit(0).` após as 284 integrações IAM, todas aprovadas. O dump de 23:26:39 mostra SpringApplicationShutdownHook em TIMED_WAITING dentro de HikariPool.shutdown/ThreadPoolExecutor.awaitTermination; o log também contém tentativas de conexão recusadas durante o encerramento. Isso indica atraso de limpeza do contexto/pool dos testes, sem provar uma causa definitiva. Não há execução JMeter nessa JVM padrão. Não foi ampliado o escopo para alterar o lifecycle dos testes existentes: acompanhar essa ocorrência na revisão de estabilidade/CI e preservar os dumps locais em iam-core/target/failsafe-reports até a investigação. O build não é declarado livre de avisos de encerramento.

Revisão documental/sintática: 999 links locais válidos, 47 registros de etapas referenciados no guia, XML JMX válido, parsers PowerShell sem erros e git diff --check aprovado. Comparação em memória dos 14 arquivos alterados com cinco valores sensíveis locais: zero correspondências, sem imprimir os valores.

## Limites e próxima entrega

Duas jornadas e dois replays são quatro amostras agregadas, não quatro chamadas HTTP. Primeiro fluxo com consentimento faz sete chamadas; consentimento salvo pode reduzir para cinco; replay acrescenta uma. elapsed inclui HTTP/client setup/parsing; Latency/Connect/bytes não são instrumentados. Não comprova p95 de token, 300 req/s, SLA, HA ou memória estável.

Não cobre MFA obrigatório, refresh/OIDC, falha de senha, recurso protegido, revogação ou auditoria de carga. Windows/PowerShell foi exercitado, Linux/PowerShell 7 não. Preparação manual pode deixar registros parciais; sessões/consentimentos/histórico persistentes alteram novas execuções. Mudanças de risco da receita manual exigem instância dedicada. Encerramento forçado pode deixar arquivos privados. Nenhum dado/credencial de .env foi usado na fixture.

Próxima implementação: 10A2b, subdividindo conforme necessário. Publicação/CI e verificações gerais de entrega permanecem pendentes.

Referências: [ADR-046](../adr/046-carga-humana-pkce.md), [operação](../../load-tests/jmeter/HUMAN-PKCE.md), [runbook](../runbooks/carga-humana-pkce-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
