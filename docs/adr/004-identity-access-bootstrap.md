# ADR-004 — Identity, Access e bootstrap administrativo

- Status: implementado e validado localmente.
- Data: 18/10/2026.
- Escopo: etapa 3; base na etapa 2 e no documento de arquitetura v0.1.

## Decisão

Manter entidades e casos de uso em Java puro. Repositórios, hash de senha, auditoria e transações são portas; adaptadores usam Spring JDBC, Spring Security Crypto e TransactionTemplate. A composição dos beans fica em `bootstrap`.

JDBC é mantido nesta entrega, preservando o mecanismo introduzido na etapa 2 e tornando explícitos SQL, chaves únicas e controle de versão. A troca de adaptador de persistência fica isolada das regras de negócio.

## Identity

- E-mail é normalizado com trim e lowercase, usando `Locale.ROOT`, e é único no banco.
- Credenciais ficam em tabela separada; entidades e respostas de usuário não contêm senha/hash.
- Senhas têm de 12 a 128 caracteres, não podem ser inteiramente brancas e são preservadas sem trim.
- Argon2id usa salt de 16 bytes, hash de 32 bytes, 19 MiB de memória, duas iterações e paralelismo 1. O hash inclui os parâmetros e salt.
- Novos usuários começam ACTIVE, versão 0. ACTIVE e LOCKED podem alternar; ambos podem passar a DISABLED. DISABLED é terminal nesta versão.
- DELETE é desativação lógica. Atualizações de perfil, estado e credenciais exigem a versão atual; CAS SQL (`WHERE id=? AND version=?`) impede sobrescrita concorrente.
- Repetir o mesmo estado com a versão atual é uma operação sem alteração de versão/auditoria.

## Access

Papéis e grupos usam identificadores ASCII normalizados em maiúsculas, de 1 a 64 caracteres, iniciando com letra e aceitando letras, números e sublinhado. Permissões usam `recurso:acao`, em minúsculas, com segmentos de até 64 caracteres; curingas não são aceitos nesta etapa.

O modelo permite papéis diretos de usuário e papéis herdados de grupos. Permissões efetivas são a união sem duplicações dessas atribuições. Usuários ausentes, LOCKED ou DISABLED recebem conjunto vazio. Concessões a usuários não ativos são rejeitadas; revogações continuam disponíveis.

As relações têm chaves compostas e operações idempotentes. O adapter Access consulta suas próprias tabelas e usa `IdentityLookup` para consultar o estado de identidades. FKs entre schemas mantêm integridade referencial no banco compartilhado, sem expor repositórios entre contextos.

## Bootstrap administrativo

A administração permanece desabilitada por padrão. Ativação exige `IAM_BOOTSTRAP_ENABLED=true` e um token de 32 a 256 caracteres, sem whitespace. O script local gera um token aleatório de 256 bits, preservado em `.env`; `Start-IamCore.ps1 -EnableBootstrap` faz a ativação explícita.

Somente `/api/admin/**` aceita esse token Bearer. A comparação usa digests SHA-256 de tamanho fixo e `MessageDigest.isEqual`. O principal é `bootstrap-admin`, sem guardar o token como credencial no contexto de segurança. Headers ausentes, inválidos ou duplicados são rejeitados antes dos controllers.

As rotas são stateless e não usam autenticação por cookie, formulário ou Basic Auth. CSRF é dispensado apenas no namespace administrativo. Demais rotas continuam bloqueadas; saúde é pública. Configuração de saúde e erros da etapa 2 é preservada.

O mecanismo é temporário para desenvolvimento/bootstrap. A etapa 4 adicionará OAuth/OIDC; o bootstrap deve permanecer desligado fora de uso explícito. Um token bootstrap não é um access token OAuth nem uma identidade de usuário final.

## Auditoria e transações

Cada mutação efetiva grava auditoria na mesma transação da alteração. Falha de auditoria aborta o caso de uso, incluindo criação de credencial e atribuições. Os eventos guardam ator, tipo, recurso, correlação e metadados definidos pelo caso de uso, sem e-mail, senha, hash ou token.

A tabela inicial `iam_audit.security_events` bloqueia UPDATE, DELETE e TRUNCATE por triggers. A etapa 8 deverá separar privilégios de aplicação/operação, implementar particionamento e acesso por cursor. O usuário bootstrap do banco ainda tem privilégios operacionais amplos; triggers não substituem esse isolamento.

Eventos via Outbox e RabbitMQ serão implementados na etapa 6. A auditoria atual é síncrona e transacional.

## Verificação

Testes unitários cobrem validações e casos de uso. Testcontainers verifica provisionamento HTTP, conflitos, concessões/revogações, senha Argon2id, falhas de auditoria e concorrência em PostgreSQL real. ArchUnit verifica domínio sem framework e fronteiras entre contextos. JaCoCo exige 80% de linhas por pacote selecionado de domínio/aplicação de Identity e Access.
