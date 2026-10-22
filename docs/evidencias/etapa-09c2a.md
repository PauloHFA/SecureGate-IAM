# Evidências — Etapa 9C2a

- Data: 20/10/2026; horários UTC−03.
- Branch: feat/pam-cleanup-recovery; base 54b5bea (9B2/9C1).
- Commit local desta entrega: implementação, testes e documentação da 9C2a; publicação/CI remota pendentes.

## Entrega

V21, retry persistido de limpeza com intervalos de 2–60 s, lote que permite avanço dos demais itens, contador saturado sem abandonar leases e atualização condicional que não reabre estado terminal. Métricas agregadas sem identidades, com sinalização explícita de snapshot indisponível/antigo. Não há reemissão, retenção automática ou declaração de revogação física a partir apenas da expiração.

## Execuções

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=PamCredentialServiceTest,PamCleanupMetricsTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=PamCleanupRecoveryIT,RuntimeDatabasePrivilegesIT,BrowserAuthorizationIT#pam*' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp clean verify
```

A seleção focada passou às **15:29:25**, duração Maven de **1 min e 15 s**: **62 casos**, 16 unitários e 46 integrações (oito PAM HTTP com Vault real, quatro da fila em PostgreSQL e 34 de permissões runtime), sem falhas, erros ou ignorados. Uma primeira tentativa identificou incompatibilidade de try-with-resources no teste de métricas; o fechamento explícito do registry corrigiu a compilação antes desta execução aprovada.

O build completo terminou com **BUILD SUCCESS às 15:38:13**, duração Maven de **8 min e 21 s**. Foram aprovados **741 testes**, consolidados de **69 relatórios XML** Surefire/Failsafe, sem falhas, erros ou ignorados. Inclui oito casos de ponta a ponta, arquitetura, formatação e limites de cobertura do IAM/enforcer aprovados. Logs locais ignorados: `.firecrawl/etapa9c2a-focused.log` e `.firecrawl/etapa9c2a-verify.log`.

Revisão complementar: 853 links Markdown locais válidos, os 41 registros de evidências de etapas referenciados no guia e `git diff --check` sem erros. A comparação em memória dos 25 arquivos alterados com cinco valores sensíveis locais da configuração não encontrou correspondências; esses valores não foram impressos nem incluídos na documentação.

Os quatro testes da fila comprovam: dez falhas cedem espaço a dois itens posteriores; serviço/repositório recriados respeitam o instante persistido, incluindo a fronteira de 2 s; apenas uma atualização concorrente com contador esperado zero vence; retry não altera registro terminal; falha da auditoria desfaz a transição final e exige nova confirmação; FAILED sem lease conhecido continua contado sem reemissão. A recriação desses objetos não reinicia a JVM; o exercício de reinício completo fica com a 9C2b.

## Limites

9C2b permanece pendente para recuperação do observador de pedidos/exercícios restantes; 9C3 para a jornada de ingestão/consulta. Não houve alteração da stack persistente, publicação ou consulta de CI. Os testes usam containers descartáveis. Pausa/retomada de um backend não comprova reconstrução após perda de estado do Vault dev.

Referências: [ADR-041](../adr/041-recuperacao-da-limpeza-pam.md), [operação](../runbooks/recuperacao-pam-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
