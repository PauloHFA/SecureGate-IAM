# Evidências — Etapa 9B1

- Data: 20/10/2026, horários UTC−03.
- Branch: `feat/privileged-access-requests`, base `17aea1c` (9A2c).
- Commit local: inclui a entrega e suas evidências; publicação e CI remota pendentes.

## Entrega

V18, pedidos persistidos e terminais por expiração/revogação, autorização corrente com escopo dedicado e MFA obrigatório do sid original. Alvo fixo de servidor, prazo limitado por pedido/token/política/prova, respostas sem segredo/justificativa. Auditoria e Outbox transacionais na criação/revogação. Runtime pode inserir/ler e atualizar somente a revogação. Launcher opcional `-EnablePam`.

## Validação

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=PrivilegedRequestServiceTest,ResourceAccessServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=BrowserAuthorizationIT#pam*,RuntimeDatabasePrivilegesIT,IamCoreIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd -B -ntp '-Dit.test=PolicyAdministrationIT,RevocationConsumerIT,PropagationIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```

A seleção focada passou às 12:13:43, duração Maven de 38,264 s: **62 testes**, 20 unitários e 42 integrações (dois novos cenários PAM com OAuth/PKCE e MFA reais, dez casos básicos e trinta de permissões runtime). Zero falhas, erros ou ignorados. Log ignorado: `.firecrawl/etapa9b1-focused-confirmed.log`.

A validação geral foi dividida em duas passagens. `clean verify` executou a biblioteca, todos os unitários/arquitetura do IAM e suas 268 integrações; terminou às 12:21:27, em 6 min e 5 s, com uma falha na expectativa histórica de quantidade de migrações após V5 (12 antes, 13 com V18). Depois de corrigir somente essa expectativa, o último comando repete todos os unitários, `PolicyAdministrationIT`, os gates JaCoCo e as integrações restantes do enforcer/E2E. Não foi repetido `clean verify` integral após a correção do teste. A confirmação final passou às 12:24:07, em 2 min e 26 s, com BUILD SUCCESS em todos os módulos. Os 65 relatórios XML combinados contêm **703 casos distintos aprovados**, zero falhas, erros ou ignorados. Ambos os gates JaCoCo passaram; inclui oito cenários ponta a ponta com os JARs reais. Log final ignorado: `.firecrawl/etapa9b1-verify-final.log`. Logs locais ignorados em `.firecrawl/etapa9b1-*`; relatórios nos targets.

As primeiras execuções detectaram política de teste com restrições incompatíveis com ALLOW e precisão diferente entre Instant Java e TIMESTAMPTZ PostgreSQL. A política foi corrigida; criação/expiração passaram a usar microssegundos conservadores. O teste exige EXPIRED no limite exato retornado na criação.

## Verificações auxiliares

- `git diff --check` aprovado.
- Launcher PowerShell analisado por AST, sem erros; não foi iniciado na stack persistente.
- 688 links locais Markdown verificados sem referências quebradas.
- Sete valores locais de credenciais comparados em memória com os arquivos alterados, sem correspondências e sem exibir valores.
- Testes runtime confirmam SELECT/INSERT/revogação e negam alteração de justificativa/prazo e DELETE. A V18 é aplicada nos bancos de teste.

## Limites

Sem emissão de segredo, grant de acesso PostgreSQL, consumo único do pedido ou integração PAM com Vault. Sem scheduler/auditoria de expiração, expurgo ou auditoria completa de recusas. Esses itens pertencem à 9B2/9C. Nenhuma alteração na stack persistente ou credencial local foi necessária para os testes.

Referências: [ADR-038](../adr/038-pedidos-de-acesso-privilegiado.md), [guia](../runbooks/pedidos-privilegiados-local.md), [plano](../PLANO-DE-IMPLEMENTACAO.md).
