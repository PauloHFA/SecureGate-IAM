# Evidências — Etapa 9C2b

- Data: 20/10/2026; horários UTC−03.
- Branch: feat/pam-request-recovery; base f11e18d (9C2a).
- Commit local desta entrega: implementação, testes e documentação da 9C2b; publicação/CI remota pendentes.

## Entrega

V22, retry persistido por pedido vencido, isolamento de falha por item e continuidade do lote, métricas do observador sem identidades e exercício de recuperação com reinício real da JVM do IAM. A transação conserva marcador/fato/Outbox; agendamento não altera TTL, origem nem estado terminal.

## Validação

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=PrivilegedRequestServiceTest,PamRequestObservationMetricsTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=PamCleanupRecoveryIT,RuntimeDatabasePrivilegesIT,BrowserAuthorizationIT#pam*' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp -DskipTests package
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=PropagationIT#pamRequestObserver*' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp clean verify
```

A seleção focada aprovou **64 casos** às **16:09:13**, duração Maven **1 min e 18 s** (16 unitários e 48 integrações: oito PAM HTTP, seis de recuperação em PostgreSQL, 34 de permissões runtime), sem falhas, erros ou ignorados.

O cenário isolado de reinício da JVM passou às **16:15:35**, duração Maven **46,626 s**, com um teste aprovado. Provoca indisponibilidade de INSERT na auditoria, confirma marcador desfeito e contador/agenda persistidos, encerra a JVM, remove a falha e inicia outra JVM com PID diferente. Confere contador/agenda sem reset, prazo/correlação originais, um fato pam.request.expired e sua Outbox; o endpoint protegido da métrica de pendências responde 200.

O build completo terminou com **BUILD SUCCESS às 16:24:05**, duração Maven de **8 min e 7 s**. Foram aprovados **746 testes**, consolidados de **70 relatórios XML** Surefire/Failsafe, sem falhas, erros ou ignorados. Inclui nove cenários de ponta a ponta, incluindo o reinício da JVM após os fluxos anteriores; arquitetura, formatação e limites de cobertura IAM/enforcer também passaram. Logs ignorados: `.firecrawl/etapa9c2b-focused.log`, `.firecrawl/etapa9c2b-jvm-restart.log` e `.firecrawl/etapa9c2b-verify.log`.

Revisão complementar: 873 links Markdown locais válidos, os 42 registros de evidências de etapas referenciados no guia e `git diff --check` sem erros. A comparação em memória dos 26 arquivos alterados com cinco valores sensíveis da configuração local não encontrou correspondências; esses valores não foram impressos nem incluídos na documentação.

## Limites

Não houve alteração da stack persistente. O reinício de processo usa fixture SQL em infraestrutura descartável, cobre o observador com emissão desabilitada e não a entrega de uma credencial real durante reinício. A jornada HTTP/credencial/ingestão/consulta permanece na 9C3. Perda de estado do Vault dev, reconciliação SQL privilegiada automática e retenção continuam fora desta entrega.

Referências: [ADR-042](../adr/042-recuperacao-do-observador-pam.md), [operação](../runbooks/recuperacao-pam-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
