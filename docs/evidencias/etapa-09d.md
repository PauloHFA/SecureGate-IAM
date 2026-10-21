# Evidências — Etapa 9D: revisão de controles e aceite técnico local

- Data: 20/10/2026, UTC−03.
- Base revisada: 6d08c2a (9A3).
- Branch: docs/stage-nine-acceptance; entrega documental, incluída neste commit; push pendente.
- Escopo: consolidar critérios e limitações da etapa 9 e preparar avanço local para 10A.

## Trabalho executado

Revisados critérios do plano, evidências 9A–9C, testes atuais, ACL runtime/PAM/enforcer, controller de credenciais (no-store e erros fixos), auditoria de recusas, persistência de metadados, configuração de erros/logs, exclusões Git e gatilhos da CI. A matriz de [aceite](../runbooks/aceite-etapa9.md) associa cada controle a provas e explicita limites. Não houve nova decisão arquitetural, alteração funcional, migração, execução de provisionadores ou mudança na stack persistente.

## Verificações desta revisão

- Inventário dos XML existentes: **778 testes em 72 relatórios**, zero falhas/erros/ignorados. São os resultados do build da 9A3 concluído às **22:01:30**, duração **10 min e 22 s**; não foi executado novo build nesta entrega documental.
- Suítes revisadas: IAM KV 17 unitários/2 integrações; enforcer KV 28/2; VaultDatabaseIT 12; RuntimeDatabasePrivilegesIT 34; PamCleanupRecoveryIT 6; PamAuditTest 6; PamCredentialServiceTest 15; PrivilegedRequestServiceTest 15; BrowserAuthorizationIT 98 (inclui oito cenários PAM); PropagationIT 11. Contagens são subconjuntos do total, não novos testes.
- `git check-ignore .env infra/.local/policy-enforcer-secret-id integration-tests/target/e2e/iam-core.log`: três caminhos ignorados.
- `git ls-files .env '*/.local/*' '*.key' '*.pem' '*.p12' '*.jks'`: nenhuma correspondência. Verificação de nomes do índice atual; não é scan de conteúdo nem do histórico.
- Workflow .github/workflows/ci.yml inspecionado localmente: verify Java 21/Maven e relatórios, acionado em PR, push main ou manual. Não houve consulta ao GitHub nem comprovação de execução remota.
- Validação documental: 948 links locais válidos, sem referências quebradas; 45 registros de etapas referenciados no guia. git diff --check aprovado.

## Resultado e pendências

Aceite técnico local consolidado na matriz; próximo trabalho local: 10A, massa sintética e planos JMeter. O aceite global/remoto não é declarado: publicação/CI/merge continuam separados e a parte de ausência de segredos em imagens depende das entregas 11A/11B. Metas de desempenho pertencem à etapa 10. Vault dev volátil, snapshot KV, resposta PAM ambígua e sessão já aberta durante indisponibilidade permanecem limites documentados, sem garantia ampliada por esta revisão.

Referências: [matriz e reprodução](../runbooks/aceite-etapa9.md), [evidências 9A3](etapa-09a3.md), [plano](../PLANO-DE-IMPLEMENTACAO.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
