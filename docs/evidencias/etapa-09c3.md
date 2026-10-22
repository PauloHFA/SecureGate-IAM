# Evidências — Etapa 9C3

- Data: 20/10/2026; horários UTC−03.
- Branch: feat/pam-integrated-acceptance; base bb48f3f (9C2b).
- Commit local desta entrega: implementação, testes e documentação da 9C3; publicação/CI remota pendentes.

## Entrega

Dois cenários de jornada PAM com JARs reais, runtime restrito sem Flyway, MFA via HTTP, emissão/uso/revogação de credencial real, falhas de Vault/RabbitMQ, reinício do IAM e consulta central por pool de ingestão. Correção de bootstrap OAuth para não exigir bean Flyway ausente quando migrações são externas. Não há nova migração: V22 permanece a versão atual.

## Comandos

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -DskipTests package
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=PropagationIT#pamJourney*' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp clean verify
```

Execução inicial encontrou a referência obrigatória a flywayInitializer no bootstrap OAuth; corrigida antes da validação final. O cenário de broker também foi ajustado para aguardar todos os fatos, que podem chegar fora de ordem por backoff. A verificação da identidade do pool dedicado abre uma consulta antes de conferir pg_stat_activity, pois o pool começa com minimumIdle=0.

A seleção focada passou com **2 cenários**, sem falhas, erros ou ignorados, às **20:35:49**, duração Maven de **2 min**. Os dois nomes aparecem com PASS em resilience.csv: pam_runtime_isolation_vault_outage_jvm_restart_central_audit e pam_expiration_broker_outage_recovery_central_query_cursor. São provas de uso/revogação com lease real, reinício do IAM, identidades distintas, broker offline/retomado e consulta HTTP central/cursor.

O build completo terminou com **BUILD SUCCESS às 20:46:17**, duração Maven de **10 min e 4 s**. Foram aprovados **748 testes**, consolidados de **70 relatórios XML** Surefire/Failsafe, sem falhas, erros ou ignorados. Inclui **11 cenários de ponta a ponta**: os fluxos anteriores e os dois novos cenários PAM passaram juntos. Arquitetura, formatação e limites de cobertura IAM/enforcer também foram aprovados. Logs ignorados em `.firecrawl/etapa9c3-*.log`; resultados de processo em `integration-tests/target/e2e/`, também ignorados.

Revisão complementar: 903 links Markdown locais válidos, os 43 registros de evidências de etapas referenciados no guia e `git diff --check` sem erros. A comparação em memória dos 14 arquivos alterados com cinco valores sensíveis da configuração local não encontrou correspondências; esses valores não foram impressos nem incluídos na documentação.

## Limites

Não houve alteração da stack persistente. Testes usam identidades/senhas geradas, arquivos temporários e infraestrutura descartável. Aceite não comprova HA/SLA, recuperação de perda do Vault dev, emissão do login runtime por Vault ou configuração de produção. KV do enforcer, hardening e aceite final da etapa 9 permanecem pendentes.

Referências: [ADR-043](../adr/043-aceite-integrado-pam.md), [operação](../runbooks/jornada-pam-integrada-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
