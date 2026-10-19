# Evidências — 5D3: autorização por operação nos recursos

- Data: 2026-10-19.
- Branch: `feat/resource-operation-authorization`, base `df0d1c9` (5D2).
- Escopo: recurso local de leitura da própria identidade, políticas reais por requisição e contrato com o enforcer.
- Commit/publicação/CI remota/revisão/merge: pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com JDK 21, PostgreSQL 16.15 e Vault 1.21.4 temporários: **401 testes, zero falhas, zero erros e zero ignorados**. Duração aproximada: 2 minutos e 3 segundos. Spotless, regras de arquitetura e cobertura JaCoCo mínima de 80% por pacote de domínio/aplicação aprovados.

O build final rodou em cópia isolada para evitar interferência da IDE em `target`. Os 228 arquivos relevantes de código/configuração/scripts coincidem por SHA-256 com as fontes validadas. JAR copiado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa5d3-verify.log`; relatórios em `.firecrawl/etapa5d3-reports/`, ignorados pelo Git.

Os 70 testes HTTP de navegador incluem seis novos casos do recurso. Cinco testes unitários de `ResourceAccessService` e três de `LocalResourceTokens` completam a cobertura nova. Links locais e exemplos PowerShell foram conferidos; arquivos alterados foram comparados com as credenciais locais sem correspondências. `git diff --check` aprovado.

## Casos cobertos

- Sem Bearer, valor inválido, assinatura adulterada e ID token: rejeitados. Audiência estrangeira é rejeitada pelo adaptador; Client Credentials não se torna identidade humana.
- Política da emissão não autoriza o recurso: ausência de política própria nega; ALLOW_LIMITED permite escopo obrigatório e limita prazo; DENY adicionado impede o acesso seguinte.
- Cookie de login não substitui Bearer; método POST não mapeado é negado sem registrar permissão da operação GET.
- Resposta contém somente sujeito, escopos efetivos e prazo, com no-store e sem criação de cookie. Permissão exige registro de auditoria com ator/alvo/ação.
- Refresh substitui o access token aceito, revogação local rejeita o token atual e mudança de versão da identidade impede acesso com o snapshot anterior.
- MFA adicionado pela política exige prova da sessão original; comprovação em outro navegador não serve; recuperação na sessão correta permite e logout remove a prova.
- TTL da política é contado desde emissão, incluindo fronteira exata de expiração, sem janela deslizante por chamada. Escopos de decisão não ampliam os do grant.
- Falhas de política ou auditoria produzem 503 sanitizado. Falha de armazenamento não resulta em validação offline permissiva.
- Regressão dos fluxos OAuth/OIDC, navegação, MFA, risco, identidade/acesso e política preservada.

## Aceite e limites

A 5D3 conclui a aplicação local por operação prevista na etapa 5 e documenta o contrato interno `ResourceTokens`. Há uma única operação de demonstração: `GET /api/resources/account`, com `account:self` / `read` / `api:read` fixados no servidor. Não se declara proteção automática de novos endpoints nem de recursos externos.

As verificações são online no `iam-core`, sem Redis ou consumidores do enforcer. Não há medição de propagação distribuída abaixo de dois segundos; esse aceite continua na etapa 7. Sessões/retornos continuam locais. O ponto de verificação não serializa a leitura com mudanças concorrentes futuras. Logout invalida prova MFA, sem promessa de revogação automática de todo JWT sem exigência MFA.

Sem novas migrações ou alteração de credenciais, Compose ou infraestrutura persistente. Nenhum servidor persistente foi iniciado. A próxima entrega é a 6A, contrato dos eventos e Outbox transacional.

Referências: [ADR-017](../adr/017-autorizacao-por-operacao-nos-recursos.md), [guia](../runbooks/recursos-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).
