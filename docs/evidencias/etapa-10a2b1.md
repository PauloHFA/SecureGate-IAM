# Evidências — Etapa 10A2b1: falhas de login

- Data: 21/10/2026; horários UTC−03.
- Base: 8580b4e (10A2a); branch feat/load-login-failures-smoke.
- Estado: implementação local, incluída neste commit; push/CI remota pendentes.

## Entrega e divisão

Gerador de usuários por API, senha CSPRNG e candidato ausente aleatório; plano JMeter JSR223/Groovy de senha incorreta/usuário inexistente, com CSRF real, cookies isolados, mensagem genérica comparada e navegador anônimo confirmado. Runner com relatórios/contagens e injeção de senha correta para detectar aceitação inesperada. Teste opt-in com serviços/JARs reais. Não há alteração de aplicação, schema, ACL, bloqueios ou stack persistente. V22 permanece atual.

A 10A2b foi dividida: 10A2b1 cobre estas negativas, 10A2b2 tratará recursos/revogação e 10A2b3 auditoria. A 10A/etapa 10 ainda não estão concluídas.

## Ambiente e comandos

Java 21, PowerShell Windows, Docker Desktop 29.9.1 e JMeter 5.6.3 já conferido/instalado na 10A1. Mesmas imagens descartáveis PostgreSQL 16.15-alpine, Redis 7.4.11-alpine, RabbitMQ 4.3.6-management-alpine e Vault 1.21.4. O teste configura perfil de risco sintético apenas na fixture para tornar o controle de senha válida reprodutível; o gerador não muda risco/MFA/Redis.

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterLoginFailureSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify
.\mvnw.cmd -B -ntp clean verify
```

## Smoke opt-in

**BUILD SUCCESS às 00:07:12**, duração Maven **46,459 s**: **1 teste**, zero falhas/erros/ignorados. JaCoCo desativado nessa seleção; gates pertencem ao build padrão.

| Execução | login.invalid_password.expected | login.unknown_user.expected | Resultado |
| --- | --- | --- | --- |
| Uma thread/uma iteração, massa por API | 1 amostra, 0 falhas | 1 amostra, 0 falhas | Runner aprovado; dashboard HTML presente. |
| Mesma massa com UseCorrectPassword | 1 amostra, 1 falha; sessão autenticada confirmada (HTTP 200) | 1 amostra, 1 falha; código local 599 sem chamada HTTP | Runner recusado, conforme exigido pelo teste. |

A execução normal comprovou 302 para erro de login, alerta genérico igual nos dois casos, ausência de dados da massa na página de erro e redirect para login ao consultar /mfa. A fixture confirmou por SQL que o candidato não existia antes do ensaio e aguardou exatamente dois novos fatos auth.login.failed na Outbox depois dele; essas consultas ficaram fora da parte cronometrada.

O controle com senha correta confirmou autenticação real na página /login e registrou unexpected_authentication. O segundo caso não foi enviado após essa violação: código 599 indica falta da referência de recusa válida, não resposta do IAM. O controle negativo é prova de detecção e não uma falha operacional não resolvida.

Busca de token administrativo, senha, IDs/e-mails sintéticos e formato JWT nos artefatos textuais não encontrou correspondências. Token/CSV privados removidos em finally. Relatórios revisados preservados em .firecrawl/etapa10a2b1-artifacts antes do clean; log da seleção .firecrawl/etapa10a2b1-smoke.log. Tudo ignorado, sem publicação automática.

## Build padrão e revisão

Build padrão **BUILD SUCCESS às 00:17:56**, duração Maven **10 min e 15 s**, em **21/10/2026**: **778 testes em 72 relatórios XML**, zero falhas/erros/ignorados, incluindo os **11 cenários E2E** anteriores. Arquitetura, Spotless e limites JaCoCo IAM/enforcer aprovados. Log ignorado .firecrawl/etapa10a2b1-verify.log. Os XML não contêm suítes JMeter: o smoke opt-in foi executado separadamente e não é somado aos 778. O clean removeu seus XML/artefatos anteriores; log e cópia sanitizada local preservam o resultado.

A ocorrência de encerramento tardio da JVM/Hikari registrada na 10A2a **não se repetiu nesta execução**: não houve mensagem kill self fork JVM nem dumps de encerramento no diretório Failsafe IAM. Não foi aplicada correção de lifecycle nem declarada uma causa/resolução definitiva para a ocorrência anterior.

Revisão documental/sintática: 1026 links locais válidos, 48 registros de etapas referenciados no guia, XML JMX válido, parsers PowerShell sem erros e git diff --check aprovado. Comparação em memória dos 15 arquivos alterados com cinco valores sensíveis locais: zero correspondências, sem imprimir os valores.

## Limites e próxima entrega

Duas amostras aprovadas agregam oito chamadas e parsing. elapsed não é tempo isolado de POST /login; Latency/Connect/bytes do JSR223 não são instrumentados. Não mede tempo constante, enumeração por timing, capacidade, p95, 300 req/s, SLA ou memória estável.

Não cobre bloqueios sob carga, MFA, recuperação de conta, acesso autenticado/revogação nem ingestão/consulta central sob carga. A Outbox foi conferida como prova de origem, não como aceite da 10A2b3. Repetir tentativas pode acionar controles reais e gerar 403/429/503, que falham o plano sem limpar projeções. Controle de senha correta pode criar sessão no servidor; usar ambiente dedicado. Criação parcial pode deixar usuários, e encerramento forçado pode deixar arquivos privados. Windows/PowerShell foi exercitado; Linux/PowerShell 7 não. Nenhuma credencial de .env foi usada na fixture.

Próxima implementação: 10A2b2, recursos/revogação; depois 10A2b3, auditoria. Publicação/CI e verificações gerais de entrega permanecem pendentes.

Referências: [ADR-047](../adr/047-carga-de-falhas-de-login.md), [operação](../../load-tests/jmeter/LOGIN-FAILURES.md), [runbook](../runbooks/carga-falhas-login-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
