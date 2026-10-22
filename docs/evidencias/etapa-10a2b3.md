# Evidências — Etapa 10A2b3: auditoria central

- Data: 21/10/2026; horários UTC−03.
- HEAD/base: 860d125 (10A2b1); branch feat/load-audit-smoke.
- Continuidade: preservados os 16 arquivos da 10A2b2 ainda sem commit; essa entrega não foi commitada/publicada automaticamente.
- Estado: 10A2b2/10A2b3 implementadas localmente, incluídas neste commit; push/CI remota pendentes.

## Entrega

Novo plano JMeter produz dois usuários/fatos por iteração pela API administrativa, consulta log central por correlação/tipo com janela fixa, continua por cursor real e exige recusa sem credencial. Quatro rótulos, runner com contagens/artefatos redigidos, token administrativo por arquivo e controle de correlação inexistente. Helper audit-only na fixture provisiona os logins restritos existentes e habilita consumidor/consulta sem PAM. Não há inserção direta de envelopes, purga de filas ou alteração de aplicação/schema/ACL/stack persistente. V22 permanece atual.

A 10A reúne agora planos funcionais dos cenários previstos. A etapa 10 continua pendente de observabilidade 10B, medição sustentada 10C e ajustes 10D; nenhum aceite de capacidade/SLA foi declarado.

## Ambiente e execução

Java 21, PowerShell Windows, Docker Desktop e JMeter 5.6.3 já instalado/conferido. PostgreSQL 16.15-alpine, Redis 7.4.11-alpine, RabbitMQ 4.3.6-management-alpine e Vault 1.21.4 descartáveis, JARs reais. Arquivo da identidade de ingestão gerado na fixture; não usa .env.

Primeira seleção incluiu JMeterAuditSmoke e JMeterResourceRevocationSmoke, com spotless:apply. A regressão de recurso/revogação passou (1 teste, 48,86 s), incluindo suas dez amostras normais e controle de revogação omitida. O novo script encontrou conflito de nome na compilação Groovy (closure/variável parameters), antes de executar HTTP. Renomeado para queryParameters/queryValues; não foi necessário alterar a aplicação. Log inicial ignorado: .firecrawl/etapa10a2b3-initial-smoke.log. Esse build inicial falhou pelo erro corrigido; não é apresentado como seleção global aprovada.

Reexecução focalizada após a correção:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterAuditSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify
```

**BUILD SUCCESS às 00:45:27**, duração Maven **1 min e 4 s**. **1 teste**, zero falhas/erros/ignorados, JMeterAuditSmoke em **57,48 s**. Compilação/empacotamento, Spotless check e Failsafe aprovados. Testes unitários e JaCoCo foram excluídos nessa seleção; não foi repetido clean verify. Os 778 testes do build padrão continuam como resultado histórico da [10A2b1](etapa-10a2b1.md), sem somar smokes opt-in ou afirmar nova execução.

## Contratos observados

Duas threads/uma iteração; cada execução cria quatro usuários/fatos:

| Rótulo | Normal: amostras/falhas | WrongCorrelation: amostras/falhas |
| --- | --- | --- |
| audit.produce.success | 2 / 0 | 2 / 0 |
| audit.visible.expected | 2 / 0 | 2 / 2 (HTTP 200 vazio, audit_contract_failed) |
| audit.cursor.success | 2 / 0 | 2 / 2 (599 local, sem cursor/chamada) |
| audit.unauthorized.expected | 2 / 0 | 2 / 0 (HTTP 401) |

Na execução normal, as páginas têm IDs distintos, tipo identity.user.created, correlação esperada, janela preservada, producer/schema conferidos e recursos correspondentes exatamente aos usuários criados. Segunda página encerra cursor; ordem temporal decrescente conferida. Controle com correlação ausente falha o runner, mesmo com HTTP 200 da API; a consulta sem credencial permanece independente e recebe 401.

Fora da parte cronometrada, o teste aguardou oito fatos publicados e oito registros em iam_audit.event_log e exigiu oito correspondências por ID/payload JSONB com a Outbox, incluindo os fatos reais produzidos durante o controle. O helper habilitou ingestão/consulta com securegate_audit_ingest_login; o plano usa somente HTTP.

Dashboard HTML confirmado. Busca em artefatos textuais por token administrativo, credencial de infraestrutura da fixture, IDs/e-mails humanos e padrões JWT/senha Audit!: zero correspondências. Arquivo administrativo removido em finally. Artefatos ignorados em integration-tests/target/audit-smoke/<execução>; log aprovado .firecrawl/etapa10a2b3-smoke.log. A execução inicial com erro permanece em outro diretório ignorado, não é evidência de aprovação. Logs de serviços têm escopo distinto e não são automaticamente publicáveis.

## Revisão e limites

Parser PowerShell e XML JMX aprovados; git diff --check sem erros. Conferidos 1086 links Markdown locais, sem destinos ausentes, e os 50 registros de etapas estão referenciados no guia. Comparação em memória dos 25 arquivos alterados (incluindo a 10A2b2 preservada) com cinco valores sensíveis locais: zero correspondências, sem imprimir valores.

Produção agrega criação/hash/escrita de dois usuários; visibilidade agrega polling/espera/publicação/consumo/consulta. elapsed inclui HttpClient/parsing; Latency/Connect/bytes não foram instrumentados. Polling tem prazo 10 s mais chamada em curso (timeout 5 s). Janela fixa [início−5 s, início+300 s) com limit=1. Não é medição isolada de ingestão, taxa máxima, p95, estabilidade, tracing ou SLA; consulta não mantém snapshot.

Cobertura limitada a identity.user.created, correlação/tipo/janela/cursor e falta de credencial. Retenção, arquivo, replay/deduplicação/conflitos, indisponibilidade/recuperação e outros filtros/tipos não são exercitados pelo novo plano. Cada iteração deixa dois usuários ativos e fatos permanentes no modo manual; falha parcial não é desfeita. Usar stack dedicada; sem limpeza de banco/Redis/filas. Interrupção forçada pode deixar arquivo administrativo privado. Windows validado; Linux não exercitado. Publicação/CI/merge continuam pendentes.

Próxima implementação: **10B — observabilidade**, antes de ensaios de capacidade em 10C.

Referências: [ADR-049](../adr/049-carga-de-auditoria-central.md), [operação](../../load-tests/jmeter/AUDIT.md), [runbook](../runbooks/carga-auditoria-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
