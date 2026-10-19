# Evidências — 5D1: aplicação de políticas na emissão de tokens

- Data: 2026-10-18.
- Branch: `feat/policy-token-enforcement`.
- Escopo: emissão Authorization Code e refresh de usuário, com default deny, MFA, escopos e TTL.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado em cópia isolada das fontes, com JDK 21 e PostgreSQL 16.15/Vault 1.21.4 temporários: **373 testes, zero falhas, zero erros e zero ignorados**. Duração aproximada: 1 minuto e 36 segundos. Spotless, arquitetura e limite JaCoCo de 80% por pacote de domínio/aplicação aprovados.

Os 214 arquivos de código/configuração/scripts relevantes foram comparados por SHA-256 com a cópia validada. O isolamento evita interferência do build automático da IDE. JAR preservado em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa5d1-verify.log`; relatórios em `.firecrawl/etapa5d1-reports/`, ignorados pelo Git.

O aceite inclui 55 casos de navegador e novos testes unitários de decisão, validade e isolamento do contexto por execução. O teste anterior de expiração de cadastro MFA usa relógio na precisão de microssegundos persistida pelo PostgreSQL, tornando a fronteira exata determinística. Documentação e exemplos locais foram atualizados para a exigência de política explícita.

## Casos

- Política ausente/desabilitada ou DENY impede entrega; habilitar ALLOW permite repetir um código ainda válido e não consumido.
- ALLOW_LIMITED reduz JWT, resposta OAuth e modelo persistido; ID token/userinfo omitem claims pessoais sem escopo permitido. Retirar `openid` do conjunto efetivo solicitado nega a emissão.
- Refresh aplica limites atuais e não restaura escopos removidos; tentativa explícita fora do grant continua `invalid_scope` sem consumo.
- Prova MFA deve pertencer ao sid original; outra sessão não satisfaz. Expiração impede refresh até nova comprovação válida nessa sessão.
- Validade de access token limitada pelo prazo da prova MFA; estado temporário removido após falha e uma única decisão reutilizada por execução, sem alterar configuração persistida do cliente.
- ABAC de rede usa evidência coletada do navegador; permitir a conexão na classificação real altera a próxima avaliação.
- Falha de coletor/política retorna 503 sem fallback; falha de auditoria após geração faz rollback da emissão e permite repetição segura.
- Regressão dos fluxos anteriores, inclusive replay/concorrência, refresh, OIDC e Client Credentials.

## Limites

Não há desafio automático na navegação, bloqueio da verificação de senha, política humana para Client Credentials ou proteção de recursos nesta entrega. JWTs já entregues não são revogados automaticamente por mudança posterior da política. Não se declara a etapa 5 encerrada.

Sem novas migrações ou alteração de credenciais/stack persistente. Usuários/clientes existentes precisam de política explícita para novas emissões/renovações de usuário. Publicação, CI remota, revisão e merge pendentes.

Referências: [ADR-015](../adr/015-politicas-na-emissao-de-tokens.md), [guia](../runbooks/politicas-tokens-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
