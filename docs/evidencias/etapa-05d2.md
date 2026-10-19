# Evidências — 5D2: desafio MFA na autorização

- Data: 2026-10-19.
- Branch: `feat/mfa-authorization-navigation`.
- Escopo: desafio interativo, retorno protegido e reavaliação da aprovação de consentimento.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com JDK 21 e PostgreSQL 16.15/Vault 1.21.4 temporários: **387 testes, zero falhas, zero erros e zero ignorados**. Duração aproximada: 1 minuto e 42 segundos. Spotless, arquitetura e limite JaCoCo de 80% por pacote de domínio/aplicação aprovados.

A compilação foi executada em cópia isolada para evitar interferência da IDE em `target`. Os 221 arquivos relevantes de código/configuração/scripts foram comparados por SHA-256 com as fontes validadas. JAR preservado em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa5d2-verify.log`; relatórios em `.firecrawl/etapa5d2-reports/`, ignorados pelo Git.

Os 64 casos HTTP de navegador incluem nove casos novos da 5D2. Testes unitários adicionais cobrem orientação de navegação, vínculo/prazo e consumo do retorno. Documentação, links locais e exemplos PowerShell conferidos. Não foi iniciado servidor persistente nem alterado o Compose local para esse aceite.

## Casos cobertos

- Desafio antes de criar consentimento, ativação com códigos de recuperação visíveis e retorno ao fluxo OIDC preservando state, nonce e PKCE.
- Retomada por POST com CSRF, ticket de uso único e destino interno fixo, sem aceitar URL arbitrária do navegador.
- Exigência MFA adicionada durante consentimento e prova vencendo na tela provocam novo desafio antes de registrar aprovação/código.
- Recuperação de fator ativo, retorno com expiração absoluta, leitura sem prorrogação e rejeição de ticket vencido/alterado/de outro navegador.
- Reautenticação limpa retorno; serviço verifica usuário, versão, sid, prova e prazo. Novo desafio substitui anterior.
- DENY introduzido durante MFA impede a autorização ao retornar. Indisponibilidade retorna 503 sem consentimento/código como fallback.
- `prompt=none` usa erro do protocolo sem criar desafio; redirect inválido permanece rejeitado localmente. Usuário pode negar consentimento sem completar MFA.
- Regressão de emissão/refresh, limites, auditoria, MFA, OIDC, Identity/Access, política e risco.

## Limites

Retorno é local à sessão/instância e dura no máximo cinco minutos. Não há sessão distribuída, transferência entre navegadores ou desafio interativo pelo endpoint de refresh. A 5D3/enforcer ainda precisa proteger recursos por operação. Não se declara a etapa 5 concluída.

Sem novas migrações ou alterações de credenciais/stack persistente. Publicação, CI remota, revisão e merge pendentes. Referências: [ADR-016](../adr/016-desafio-mfa-na-autorizacao.md), [guia](../runbooks/desafio-mfa-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
