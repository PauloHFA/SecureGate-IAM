# SecureGate IAM

Plataforma de identidade e acesso baseada em Zero Trust, com provisionamento, OAuth/OIDC, políticas contextuais, eventos de segurança e auditoria.

## Estado atual

Fundação, infraestrutura e API administrativa de Identity/Access implementadas. O core gerencia usuários, credenciais Argon2id, grupos, papéis, permissões e auditoria transacional. Administração fica desabilitada por padrão e exige ativação explícita com token bootstrap temporário. A etapa 3 teve 106 testes no aceite original e está incluída na base publicada até a 5A. Consulte as [evidências históricas](docs/evidencias/etapa-03.md).

A entrega 4A acrescenta assinatura RS256 via Vault Transit e JWKS; a 4B integra Client Credentials/JDBC. A 4C acrescenta login, clientes web confidenciais e Authorization Code com PKCE S256 e consentimento protegido. A 4D1 implementa refresh opcional com rotação, janela absoluta de 30 minutos, detecção de reuso, introspecção e revogação pelo cliente proprietário. A 4D2 adiciona discovery, ID token Transit e userinfo com claims limitadas aos escopos aprovados. Access tokens e ID tokens duram cinco minutos. Consulte os guias [PKCE](docs/runbooks/oauth-pkce-local.md), [refresh](docs/runbooks/oauth-refresh-local.md) e [OIDC](docs/runbooks/oidc-local.md), com validação registrada nas [evidências 4D2](docs/evidencias/etapa-04d2.md).

A 5A acrescenta políticas JSONB versionadas e auditadas e uma avaliação administrativa por papéis atuais. O motor nega por padrão, faz DENY prevalecer e combina exigência de MFA com limites de escopo/TTL. A prévia administrativa permanece separada da aplicação à emissão de tokens da 5D1. Consulte o [guia de políticas](docs/runbooks/politicas-local.md) e as [evidências 5A](docs/evidencias/etapa-05a.md).

A 5B1 adiciona condições ABAC tipadas na gramática v2, risco determinístico de 0–100 e cenários administrativos separados da avaliação normal. Documentos v1 permanecem compatíveis. Consulte o [guia ABAC/risco](docs/runbooks/abac-risco-local.md) e as [evidências 5B1](docs/evidencias/etapa-05b1.md).

A 5B2 coleta contexto de navegador autenticado, rede da conexão, falhas reais de login e sensibilidade administrada de recursos. Aprovação de navegador tem prazo e versão; contextos de outro usuário, antigos ou fechados não fornecem confiança. Há cinco minutos de aquecimento do contador após iniciar o processo. Consulte o [guia de coleta](docs/runbooks/coleta-risco-local.md) e as [evidências 5B2](docs/evidencias/etapa-05b2.md).

A 5C1 inicia MFA com TOTP e proteção da semente por uma chave AES-256-GCM separada no Vault Transit, vinculada ao usuário e à inscrição. A 5C2 acrescenta cadastro/ativação em `/mfa`, recuperação de uso único, limite de tentativas por conta e prova de step-up de cinco minutos vinculada à sessão. Consulte o [guia MFA](docs/runbooks/mfa-local.md), as [evidências 5C2](docs/evidencias/etapa-05c2.md) e a [base criptográfica](docs/runbooks/mfa-base-local.md).

A 5D1 exige política para emissão e renovação de tokens de usuário: alvo `oauth:<client_id>`, ação `issue`. Sem permissão correspondente, a troca é negada; limites reduzem escopos/TTL e MFA exige prova no sid original. Isso vale também para clientes existentes, sem ALLOW automático. Consulte o [guia de políticas na emissão](docs/runbooks/politicas-tokens-local.md) e as [evidências 5D1](docs/evidencias/etapa-05d1.md).

A 5D2 integra o desafio MFA à navegação de autorização, antes do consentimento e novamente na aprovação. O retorno exige CSRF, ticket de uso único, prazo e a mesma identidade/sessão; preserva state, nonce e PKCE e revalida a autorização. A aplicação por operação nos recursos continua na 5D3/enforcer. Consulte o [guia do desafio MFA](docs/runbooks/desafio-mfa-local.md) e as [evidências 5D2](docs/evidencias/etapa-05d2.md).

## Decisões aprovadas

- Java 21, Spring Boot e Spring Authorization Server.
- Maven com Wrapper e build multimódulo.
- Vault Transit para assinatura JWT desde a etapa de autenticação.
- PostgreSQL, Redis e RabbitMQ para dados, estado distribuído e eventos.

## Documentação

- [Roteiro de implementação e acompanhamento](docs/PLANO-DE-IMPLEMENTACAO.md)
- [ADR-001 — Stack e organização](docs/adr/001-stack-e-organizacao.md)
- [ADR-002 — Assinatura JWT com Vault Transit](docs/adr/002-assinatura-jwt-vault-transit.md)
- [Infraestrutura local: execução, credenciais e diagnóstico](infra/README.md)
- [ADR-003 — Baseline da infraestrutura](docs/adr/003-infraestrutura-local.md)
- [Execução e diagnóstico do iam-core](docs/runbooks/iam-core-local.md)
- [Evidências da etapa 2](docs/evidencias/etapa-02.md)
- [Provisionamento administrativo local](docs/runbooks/provisionamento-local.md)
- [ADR-004 — Identity, Access e bootstrap administrativo](docs/adr/004-identity-access-bootstrap.md)
- [ADR-005 — Integração Transit e publicação de chaves](docs/adr/005-transit-e-jwks.md)
- [ADR-006 — Authorization Server JDBC e Client Credentials](docs/adr/006-authorization-server-client-credentials.md)
- [ADR-007 — Login, PKCE e consentimento](docs/adr/007-login-pkce-e-consentimento.md)
- [ADR-008 — Refresh, introspecção e revogação](docs/adr/008-refresh-rotacao-e-revogacao.md)
- [ADR-009 — OIDC, discovery e userinfo](docs/adr/009-oidc-discovery-e-userinfo.md)
- [ADR-010 — Políticas e tabela de decisão](docs/adr/010-politicas-e-tabela-de-decisao.md)
- [ADR-011 — ABAC e risco determinístico](docs/adr/011-abac-e-risco-deterministico.md)
- [ADR-012 — Coleta confiável de sinais de risco](docs/adr/012-coleta-confiavel-de-risco.md)
- [ADR-013 — Base TOTP e cifra de segredos pelo Transit](docs/adr/013-base-totp-e-cifra-transit.md)
- [ADR-014 — Cadastro MFA, recuperação e prova de step-up](docs/adr/014-cadastro-mfa-recuperacao-e-step-up.md)
- [ADR-015 — Políticas na emissão e renovação de tokens](docs/adr/015-politicas-na-emissao-de-tokens.md)
- [ADR-016 — Desafio MFA na navegação da autorização](docs/adr/016-desafio-mfa-na-autorizacao.md)

O roteiro deriva do documento **SecureGate IAM – Documentação e Plano de Implementação**, versão 0.1, outubro de 2026, disponível em PDF na pasta local do projeto.

## Próximo passo

Seguir para a 5D3: aplicação das decisões por operação nos recursos, coordenada com o enforcer. O [PR #4](https://github.com/PauloHFA/SecureGate-IAM/pull/4) reúne as etapas 0–2. A base até a 5A (`8f5666d`) foi publicada em `feat/policy-foundation`, com [CI aprovada](https://github.com/PauloHFA/SecureGate-IAM/actions/runs/39159901831). As entregas 5B/5C/5D1 estão em commits locais, incluindo a 5D1 (`31984bd`); a 5D2 está na branch `feat/mfa-authorization-navigation`. Essas entregas aguardam publicação/CI remota. Revisão e merge continuam pendentes.

A v1 tem foco em API REST. Gateway é opcional; federação externa, WebAuthn e painel administrativo completo ficam para evoluções posteriores.

## Build local

Pré-requisitos: JDK 21, `JAVA_HOME` apontando para esse JDK, Docker Engine disponível e internet na primeira execução. O Wrapper baixa Maven 3.9.11. Testcontainers inicia PostgreSQL e Vault temporários; não é necessário subir o Compose para executar `verify`.

Windows PowerShell:

```powershell
.\mvnw.cmd -version
.\mvnw.cmd -B -ntp clean verify
```

Linux/macOS:

```sh
./mvnw -version
./mvnw -B -ntp clean verify
```

O build valida JDK/Maven, verifica formatação, executa testes unitários e de integração e gera os JARs. `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar` é executável Spring Boot; `policy-enforcer` ainda não possui bootstrap. Os testes de integração falham se Docker não estiver disponível, sem serem ignorados silenciosamente.

JaCoCo exige pelo menos 80% de cobertura de linhas por pacote de domínio/aplicação de Identity, Access, Authorization e Policy. O relatório HTML fica em `iam-core/target/site/jacoco/index.html`, e a CI inclui os relatórios de cobertura no artefato de testes.

Para executar apenas testes unitários/arquitetura sem iniciar containers, use `./mvnw test` ou `.\mvnw.cmd test`. Isso não substitui o aceite completo de `verify`. Relatórios ficam em `target/surefire-reports/` e `target/failsafe-reports/` dos módulos.

Para corrigir formatação Java, execute `.\mvnw.cmd spotless:apply` no Windows ou `./mvnw spotless:apply` no Linux/macOS. O `verify` apenas verifica a formatação, sem alterar o código.

| Módulo | Responsabilidade atual |
| --- | --- |
| `libs/security-events` | Biblioteca Java sem Spring, com validação inicial dos tipos de evento |
| `iam-core` | Identity/Access, políticas e simulação administrativa, auditoria básica, OAuth/OIDC com refresh, Transit/JWKS, Actuator e Flyway |
| `policy-enforcer` | Módulo reservado para o consumidor; implementação na etapa 7 |

O contrato inicial `SecurityEventType` aceita segmentos minúsculos separados por ponto, até 128 caracteres, e rejeita curingas de binding (`*` e `#`). O envelope versionado e a mensageria serão implementados na etapa 6. Relatórios de teste ficam em `libs/security-events/target/surefire-reports/`.

## Infraestrutura local

Com Docker Desktop em execução, na raiz do repositório:

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\infra\scripts\Test-Local.ps1
```

PostgreSQL fica em `127.0.0.1:15432`, Redis em `127.0.0.1:6379`, RabbitMQ em `127.0.0.1:5672` com painel na porta `15672`, e Vault na porta `8200`. Credenciais são geradas em `.env` e `infra/.local/`, ignorados pelo Git.

Vault usa modo dev e perde chaves ao reiniciar. Consulte o [guia de infraestrutura](infra/README.md) para reexecutar o bootstrap, operar a stack e entender os limites do ambiente local.

## Executar o iam-core

Após o build e a subida da infraestrutura:

```powershell
.\infra\scripts\Start-IamCore.ps1
```

O script lê o `.env` local e transmite a senha por variável de ambiente ao processo Java, sem gravá-la no código ou passá-la na linha de comando. A aplicação escuta em `127.0.0.1:8080`; `-Port 18080` permite trocar a porta. As migrações serão aplicadas ao banco local na primeira inicialização.

Endpoints públicos: `GET /actuator/health`, `/actuator/health/liveness` e `/actuator/health/readiness`. Por padrão, as rotas administrativas permanecem bloqueadas. Para provisionar no ambiente local, iniciar explicitamente com:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap
```

As rotas `/api/admin/**` exigem o token gerado em `.env`. Consulte o [guia de provisionamento](docs/runbooks/provisionamento-local.md) para exemplos, controle de versão e atribuições. A sessão de usuário e os JWTs OAuth não concedem acesso administrativo.

Para habilitar `GET /oauth2/jwks`, use `Start-IamCore.ps1 -EnableTransit`. Essa opção lê os arquivos AppRole de `infra/.local/` e é independente do bootstrap administrativo. Operação, rotação e diagnóstico estão no [guia Transit](docs/runbooks/transit-local.md).

Para emitir tokens máquina a máquina, use `Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap`, cadastre um cliente confidencial e solicite `client_credentials` com Basic Auth em `POST /oauth2/token`, conforme o [guia Client Credentials](docs/runbooks/oauth-client-credentials-local.md). Authorization ativa Transit automaticamente; bootstrap só é necessário para administrar. O segredo é entregue uma vez e fica armazenado como hash Argon2id.

Os mesmos parâmetros habilitam o fluxo de navegador. Cadastre o cliente em `/api/admin/oauth-clients/browser`, provisione um usuário ACTIVE e siga o [guia de login e PKCE](docs/runbooks/oauth-pkce-local.md). A sessão local dura 15 minutos por inatividade, o consentimento pendente cinco minutos e o código dois minutos. `refreshTokens: true` habilita a renovação descrita no [guia de refresh](docs/runbooks/oauth-refresh-local.md). Bloqueios e mudanças de versão da identidade impedem usar sessões/códigos/refresh antigos. Introspecção consulta o estado atual; recursos que verificam apenas assinatura JWT dependem da futura propagação de revogação ou da expiração do token.

Para autenticação OIDC, cadastre o cliente web com `oidc: true`, que adiciona `openid` automaticamente, e siga o [guia OIDC](docs/runbooks/oidc-local.md). Discovery fica em `/.well-known/openid-configuration`; userinfo exige Bearer access token do fluxo OIDC. O ID token tem audiência do cliente, e o access token, da API. O cliente deve validar assinatura, issuer, audiência, expiração e nonce antes de concluir seu login.

Para administrar políticas, basta `-EnableBootstrap`; OAuth/Transit são independentes. Use `/api/admin/policies` e `POST /api/admin/policies/evaluate`, conforme o [guia de políticas](docs/runbooks/politicas-local.md). `/api/admin/policies/simulate` testa cenários sintéticos com `simulation: true`, conforme o [guia ABAC/risco](docs/runbooks/abac-risco-local.md). Não há endpoint público de autorização de recursos nesta entrega. A política e sua auditoria são gravadas na mesma transação; edições exigem a versão atual.

Para coletar contexto real, habilite Authorization e faça login no navegador. A administração lista contextos em `/api/admin/risk/users/{userId}/contexts`; informe o `contextId` correspondente em evaluate. Redes, sensibilidade e aprovação de navegador ficam sob `/api/admin/risk/**`, conforme o [guia de coleta](docs/runbooks/coleta-risco-local.md). Headers de encaminhamento não definem a origem observada. A coleta não aplica decisões automaticamente ao login/tokens; essa integração será feita na 5D.

## Arquivos locais

A pasta `.firecrawl/` contém cache local de pesquisa e extração de documentos e é ignorada pelo Git.
