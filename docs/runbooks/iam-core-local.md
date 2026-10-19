# iam-core — Execução e diagnóstico local

## Pré-requisitos e build

Usar JDK 21, Docker com containers Linux e Maven Wrapper. O build completo usa Testcontainers com PostgreSQL 16.15 em uma porta aleatória e não depende dos dados do Compose.

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Surefire executa `*Test`; Failsafe executa `*IT` nas fases `integration-test` e `verify`. Docker ausente faz a integração falhar. `test` sozinho executa apenas testes unitários e arquitetura e não fecha o aceite da etapa.

## Iniciar com o PostgreSQL do Compose

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\infra\scripts\Start-IamCore.ps1
```

O script usa `.env` para definir `IAM_DATABASE_URL`, `IAM_DATABASE_USERNAME`, `IAM_DATABASE_PASSWORD`, `IAM_PORT` e `IAM_BIND_ADDRESS` somente no processo. Ele executa Java em primeiro plano e restaura os valores anteriores ao terminar. Para outra porta, usar `Start-IamCore.ps1 -Port 18080`.

O Flyway aplica V1 para criar os seis schemas, V2 para Identity/Access e auditoria básica, V3 para clientes/autorizações/consentimentos OAuth, V4 para famílias/histórico de refresh, V5 para políticas JSONB, V6 para ABAC v2 e V7 para sinais de risco (falhas, navegador/contexto, redes e perfis de recurso). O histórico fica em `public.flyway_schema_history`.

Migrações aplicadas não devem ser alteradas; criar um próximo arquivo `V2__...sql` para evoluções. `validate-on-migrate` está ativo e `clean` do Flyway está desabilitado. O usuário atual é o bootstrap local do PostgreSQL; separar papéis de migração/aplicação é trabalho posterior.

Sem o script, passar as variáveis explicitamente por um mecanismo local de configuração. `IAM_DATABASE_PASSWORD` não tem senha padrão no YAML. A aplicação ainda não busca sua configuração no Vault; os recursos preparados na etapa 1 serão integrados nas etapas seguintes.

## Saúde e segurança inicial

| Requisição | Comportamento |
| --- | --- |
| `GET /actuator/health` | Estado agregado e nomes dos grupos; sem componentes/detalhes |
| `GET /actuator/health/liveness` | Indica se a aplicação está viva |
| `GET /actuator/health/readiness` | Combina aceitação de tráfego e disponibilidade do banco |
| `/api/admin/**` | Bloqueadas por padrão; token bootstrap obrigatório quando ativadas |
| Outras rotas | HTTP 403 com Problem Details |

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
Invoke-RestMethod http://127.0.0.1:8080/actuator/health/readiness
```

Readiness recusa tráfego com HTTP 503 quando a aplicação está fora de serviço. Isso não deve derrubar liveness. Detalhes do banco não são publicados nas respostas.

A configuração padrão não cria usuário/senha ou sessão automática. Saúde permanece pública. Para administrar, usar `Start-IamCore.ps1 -EnableBootstrap`, conforme o [guia específico](provisionamento-local.md). As rotas administrativas autenticam exclusivamente por Bearer e dispensam CSRF. A ativação explícita de Authorization habilita Basic para clientes no token endpoint e formulário/sessão no navegador; login, consentimento e logout possuem proteção CSRF própria.

## Erros e correlação

`Start-IamCore.ps1 -EnableTransit` habilita o JWKS em `GET /oauth2/jwks`. O adaptador utiliza AppRole, sem token root no Java. `-EnableAuthorization` habilita Client Credentials e o fluxo de navegador com login/PKCE, além de refresh/OIDC opcionais, discovery OAuth/OIDC, `/userinfo`, `POST /oauth2/introspect` e `POST /oauth2/revoke`. Consulte [Client Credentials](oauth-client-credentials-local.md), [login/PKCE](oauth-pkce-local.md), [refresh/revogação](oauth-refresh-local.md) e [OIDC](oidc-local.md). As cadeias de saúde, administração e endpoints OAuth de token/introspecção/revogação/discovery/userinfo continuam sem sessão; só o navegador usa sessão HTTP local.

Cada requisição recebe `X-Correlation-ID`. Um ID recebido é aceito apenas se contiver de 1 a 64 caracteres alfanuméricos, ponto, hífen ou sublinhado, começando com alfanumérico; entradas inválidas são substituídas por UUID. O ID é incluído no MDC durante a requisição e o contexto anterior é restaurado mesmo em falha.

Erros MVC de entrada, recurso ausente, método incompatível e falhas internas usam `application/problem+json` com `type`, `title`, `status`, `detail`, `instance` e `correlationId`. O corpo não inclui query string, mensagem da exceção, stack trace ou valores rejeitados. Erros de segurança usam o mesmo formato.

Os loggers web/Boot/security têm nível INFO explícito por padrão, para evitar logs de requisições detalhados ativados por uma variável genérica `DEBUG` herdada do ambiente. O handler de erro inesperado registra apenas o tipo da falha e usa o ID de correlação do MDC. Não habilitar logging de payloads ou credenciais; um sistema completo de mascaramento e logs estruturados ainda fará parte do hardening.

As rotas `/testing/**` usadas para testar erros existem apenas nos fontes de teste, com uma cadeia de segurança exclusiva dos testes. Elas e suas permissões não entram no JAR publicado.

## Arquitetura

Os seis contextos possuem fronteiras de pacote para `domain`, `application`, `infrastructure` e `presentation`. `bootstrap` faz a composição Spring; `shared.presentation` contém o tratamento HTTP transversal.

As regras ArchUnit impedem frameworks no domínio, dependência da aplicação em adaptadores, acesso da apresentação diretamente ao domínio/infraestrutura e acesso a repositórios internos de outros contextos. Colaboração entre contextos usa os pacotes `application.port` ou `domain.event`, respeitando também as regras de camada.

Camadas de negócio ainda vazias são permitidas enquanto o scaffold é desenvolvido. Testes com fixtures intencionalmente inválidas confirmam que as regras detectam Spring no domínio, acoplamento aplicação/infraestrutura e acesso direto a repositório estrangeiro; uma fixture válida confirma colaboração por porta. Fixtures são excluídas da análise dos fontes de produção.

## CI e diagnóstico

O workflow `.github/workflows/ci.yml` executa em PRs, push para `main` e acionamento manual. Usa JDK 21, o mesmo `verify`, Docker do runner e publica relatórios de testes, inclusive em falha. Actions são fixadas por SHA, o token tem apenas `contents: read` e credenciais do checkout não são persistidas.

A sintaxe foi validada com actionlint 1.7.12 e a versão da etapa 2 também [passou no GitHub](https://github.com/PauloHFA/SecureGate-IAM/actions/runs/39137410438), com 40 testes aprovados. A etapa 3 acrescenta testes e cobertura JaCoCo; sua execução remota fica pendente até publicar a branch. Para cada novo commit, conferir os checks correspondentes à versão atual do PR.

Se o processo não inicia, verificar JDK, porta e conectividade PostgreSQL. Se o build falha antes de iniciar o contexto de integração, verificar Docker/Testcontainers. Consultar `target/surefire-reports/` e `target/failsafe-reports/`, sem compartilhar dados sensíveis dos logs.

## Referências

- [Actuator e probes no Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html).
- [Testcontainers com Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/reference/testing/testcontainers.html).
- [Evidências da etapa 2](../evidencias/etapa-02.md).
