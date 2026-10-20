# Evidências — Etapa 9B2

- Data: 20/10/2026; horários UTC−03.
- Branch: feat/pam-vault-credentials, base feaf718 (9B1).
- Implementação local sem commit/publicação/CI remota.

## Entrega

V19 e consumo único concorrente; escopo/política de emissão próprios com MFA e reautorização após Vault; entrega única de senha sem persistência. AppRole/mount/emissor e alvo diagnóstico restrito separados. Prazo SQL explícito e papéis Vault fixos. Metadados duráveis, revogação lógica própria/administrativa e worker de revogação/repetição. Compensação de falha conhecida e consumo definitivo de resultado ambíguo, sem redelivery.

## Validação

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=PamCredentialServiceTest,ResourceAccessServiceTest,PrivilegedRequestServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=BrowserAuthorizationIT#pam*' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp clean verify
```

A seleção focada passou com 32 unitários e cinco cenários HTTP reais, incluindo entrega concorrente, permissões de diagnóstico, isolamento de proprietário, falha de auditoria sem entrega, revogação administrativa e pausa/retomada do Vault. O build completo acrescentou os casos de ACL, escopo próprio e concessões do runtime, com o estado REVOKING durável.

Uma primeira verificação revelou que era necessário controlar explicitamente VALID UNTIL no SQL, em vez de depender da margem de expiração do plugin. O papel passou a usar clock_timestamp mais o TTL fixo. O teste confirmado comprova recusa de nova autenticação após o prazo e permanência de sessão ativa com Vault pausado; na retomada, o login é removido e a sessão é encerrada. Não se afirma prazo absoluto da sessão durante indisponibilidade.

Build completo **aprovado às 12:56:23**, duração Maven de **7 min e 57 s**: **722 testes**, 66 relatórios XML, zero falhas, erros ou ignorados. Ambos os gates JaCoCo e a arquitetura passaram; inclui 96 cenários de BrowserAuthorizationIT e oito cenários ponta a ponta com os JARs reais. Log local ignorado: `.firecrawl/etapa9b2-verify.log`; relatórios nos targets.

## Verificações auxiliares

- `git diff --check` aprovado.
- 706 links locais Markdown verificados, nenhum quebrado.
- Start-IamCore.ps1 e Provision-PamDatabase.ps1 analisados por AST sem erros; init.sh validado por sh -n no container Vault 1.21.4.
- pam-role.json válido; SQL/HCL exercitados nas integrações reais.
- Sete valores locais de credenciais comparados em memória com os arquivos alterados: zero correspondências, sem exibir valores.
- Scripts de provisionamento não foram executados contra a stack persistente.

## Limites

Stack persistente e segredos locais não foram alterados pelos testes. Não há redelivery, garantia de recepção da senha, SLA sob carga, limite global de leases, auditoria completa de recusas/expiração ou recuperação automática após perda do Vault dev. Uma emissão interrompida antes de persistir lease pode ficar órfã até os prazos do banco/Vault, sem ter sido entregue pela API. A 9C, KV do enforcer e hardening de produção continuam pendentes.

Referências: [ADR-039](../adr/039-emissao-pam-no-vault.md), [guia](../runbooks/credenciais-pam-local.md), [plano](../PLANO-DE-IMPLEMENTACAO.md).
