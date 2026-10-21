# Evidências — Etapa 10A2b2: recurso protegido e revogação

- Data: 21/10/2026; horários UTC−03.
- Base: 860d125 (10A2b1); branch feat/load-resource-revocation-smoke.
- Estado: implementação local, incluída neste commit na branch feat/load-audit-smoke; push/CI remota pendentes.

## Entrega e divisão

Plano de cinco rótulos: jornada PKCE, acesso ao recurso de conta com Bearer sem cookies, token inválido recusado, revogação OAuth pelo próprio cliente e recusa posterior antes da expiração. Extensão opt-in do gerador humano para política de conta ALLOW_LIMITED/api:read/120 s. Estado de tokens apenas em memória, resultados redigidos, runner com contagens exatas e controle de omissão de revogação. Nenhuma alteração de aplicação/schema/ACL/bloqueios/stack persistente; V22 permanece atual.

Conclui o recorte 10A2b2. A 10A2b3 tratará auditoria; 10B–10D cobrirão observabilidade, medição e ajustes. A etapa 10 e a 10A continuam incompletas.

## Ambiente e comando

Java 21, PowerShell Windows, Docker Desktop e JMeter 5.6.3 instalado/conferido na 10A1. PostgreSQL 16.15-alpine, Redis 7.4.11-alpine, RabbitMQ 4.3.6-management-alpine e Vault 1.21.4 descartáveis, com IAM/enforcer reais. Nenhuma credencial da stack persistente foi usada na fixture.

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterResourceRevocationSmoke,JMeterHumanSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify
```

**BUILD SUCCESS às 00:34:32**, duração Maven **1 min e 44 s**. **2 testes**, zero falhas/erros/ignorados: JMeterHumanSmoke (regressão compartilhada, 42,20 s) e JMeterResourceRevocationSmoke (55,16 s). A seleção não executa testes unitários/gates JaCoCo; não foi repetido clean verify nesta entrega. Os 778 testes do build padrão pertencem à [10A2b1](etapa-10a2b1.md) e não são apresentados como uma nova execução. Spotless aplicado no reactor; compilação/empacotamento e Failsafe aprovados.

Na primeira execução, a política de conta foi recusada na preparação: ALLOW não aceita restrições de escopo. Corrigido o gerador para ALLOW_LIMITED com TTL explícito, preservando o contrato da API e sem alterar a aplicação. A seleção completa acima foi repetida e aprovada.

## Resultados do novo plano

Duas threads/uma iteração com dois usuários/clientes sintéticos:

| Rótulo | Normal: amostras/falhas | Controle SkipRevocation: amostras/falhas |
| --- | --- | --- |
| pkce.journey.success | 2 / 0 | 2 / 0 |
| resource.success | 2 / 0 | 2 / 0 |
| resource.invalid_token.expected | 2 / 0 | 2 / 0 |
| token.revoke.success | 2 / 0 | 2 / 0 (controle local 599, sem chamada) |
| resource.revoked.expected | 2 / 0 | 2 / 2 (HTTP 200 real, resource_contract_failed) |

Execução normal: sujeito retornado confere com a massa, token malformado recebe 401, revogação recebe 200 e token original recebe 401 com validade residual. O teste exigiu dois fatos token.revoked com published_at preenchido e marcadores de processamento no Redis, fora do tempo JMeter. O recurso também consulta ledger local; recusa HTTP não isola o tempo RabbitMQ/enforcer/Redis.

Controle: nenhuma chamada ao endpoint de revogação; token permanece aceito pelo recurso após polling limitado. Runner retorna falha e o teste exige o HTTP 200 real na expectativa violada. A Outbox permanece com apenas os dois fatos da execução normal. São falhas deliberadas que comprovam detecção, sem pendência operacional.

JMeterHumanSmoke também aprovou duas jornadas PKCE/duas recusas de replay e detectou verifier incorreto. A extensão de retenção de token não é solicitada pelo plano anterior.

Dashboard HTML confirmado. Busca nos artefatos textuais por token administrativo, senhas, identificadores humanos, Basic e formato JWT: zero correspondências. Arquivos privados removidos em finally; interrupção forçada ainda pode deixá-los na pasta ignorada. Artefatos em integration-tests/target/resource-revocation-smoke e log em .firecrawl/etapa10a2b2-smoke.log, sem publicação automática.

## Revisão e limites

Parsers PowerShell e XML JMX aprovados, git diff --check sem erros e 1055 links Markdown locais válidos. Todos os 49 registros de etapas estão referenciados no guia. Comparação em memória dos 16 arquivos alterados com cinco valores sensíveis locais: zero correspondências, sem imprimir valores. Nenhum relatório de capacidade/SLA produzido.

Cada rótulo tem sua unidade; o primeiro agrega a jornada e o último agrega polling/esperas. Criação/fechamento de HttpClient e parsing entram em elapsed; Latency/Connect/bytes não são instrumentados. O prazo de polling de 10 s pode incluir uma chamada já em curso com timeout de 3 s. Dez amostras não medem p95, 300 req/s, estabilidade ou percentuais de propagação.

O plano não cobre logout/sessão, refresh token, assinatura adulterada, resource server externo, indisponibilidade ou auditoria central sob carga. ALLOW_LIMITED de conta tem rolesAnyOf vazio e se aplica a sujeitos elegíveis na instância: usar stack dedicada. Preparação parcial não é desfeita. Modo manual deixa usuários/clientes/políticas/sessões; nenhuma limpeza automática de banco/Redis. Windows validado; Linux não exercitado. Commit local registra a entrega; push/CI/merge permanecem pendentes.

Próxima implementação: **10A2b3 — auditoria**.

Referências: [ADR-048](../adr/048-carga-de-recurso-e-revogacao.md), [operação](../../load-tests/jmeter/RESOURCE-REVOCATION.md), [runbook](../runbooks/carga-recurso-revogacao-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
