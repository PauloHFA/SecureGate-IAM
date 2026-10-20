# ADR-034 — Segredos KV no bootstrap do IAM

- Data: 20/10/2026
- Estado: aceita para a entrega local 9A1

## Contexto

O bootstrap de infraestrutura já grava credenciais em KV v2 e concede ao AppRole `iam-core` leitura de `secret/data/securegate/iam-core`. O serviço Java ainda recebia essas credenciais pelo ambiente do processo. A etapa 9 inclui também credenciais dinâmicas, renovação e PAM; essas mudanças exigem entregas separadas para testar os ciclos de vida.

## Decisão

Introduzir um `EnvironmentPostProcessor`, registrado em `META-INF/spring.factories`, ativado exclusivamente por `IAM_VAULT_KV_ENABLED=true`. Ele executa depois da configuração do Spring e antes da criação do DataSource, Flyway e clientes. Obtém um snapshot da versão atual do KV por AppRole e publica somente cinco campos permitidos, mapeados para sete propriedades:

| Campo KV | Propriedade de destino |
| --- | --- |
| `postgres_username` | `spring.datasource.username` |
| `postgres_password` | `spring.datasource.password` |
| `redis_password` | `iam.resources.enforcement.password` |
| `rabbitmq_username` | `iam.outbox.publisher.username`, `iam.audit.consumer.username` |
| `rabbitmq_password` | `iam.outbox.publisher.password`, `iam.audit.consumer.password` |

O snapshot tem precedência sobre ambiente, argumentos e YAML nesses destinos. Endereços, portas e nomes de banco/vhost continuam na configuração local. Não importar propriedades arbitrárias do KV. Todos os cinco campos são obrigatórios, mesmo se um cliente opcional estiver desativado. Validar tipos, tamanho e metadados da versão; não aceitar versão destruída/excluída.

Usar HTTP somente em loopback explícito; demais hosts exigem HTTPS com validação padrão da JVM. Login, leitura e revogação têm timeout de dois segundos por requisição, sem redirects nem retry automático. RoleID/SecretID são lidos de arquivos limitados a 4 KiB. O token obtido é exclusivo dessa leitura e revogado antes de publicar o snapshot; falhas tentam a revogação e interrompem o startup. Falha de revogação também interrompe o startup. Caso o Vault esteja indisponível nessa tentativa, o token permanece sujeito ao TTL emitido pelo servidor.

Erros públicos são genéricos, sem encadear respostas HTTP, JSON ou causas que possam conter segredos. A representação textual do PropertySource é redigida. Segredos ficam na memória para os clientes; isso não oferece proteção contra inspeção de heap. Os endpoints `env`/`configprops` não são expostos pela configuração existente.

## Consequências e limites

Ativação é opcional e desabilitada por padrão. Não há fallback para credenciais locais quando ativada. O script local evita enviar as cinco credenciais equivalentes pelo ambiente nesse modo, mas continua lendo `.env` para configuração da stack; arquivos de bootstrap permanecem locais/ignorados.

O snapshot não é renovado em execução: mudar KV exige reiniciar a aplicação e coordenar a mudança no serviço correspondente. A revogação do token KV não revoga senhas estáticas. Transit mantém sua sessão independente. PostgreSQL principal continua usando a conta proprietária/migradora; contas dedicadas de auditoria continuam por arquivo. O enforcer ainda não consome KV.

Próximas entregas: **9A2** — credenciais PostgreSQL temporárias e separação migração/runtime, leases, renovação e troca segura do pool; **9B** — PAM com autorização, justificativa e TTL; **9C** — auditoria operacional e recuperação integrada. Não declarar a etapa 9 concluída pela 9A1.

Contratos oficiais consultados: [KV v2](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2), [AppRole](https://developer.hashicorp.com/vault/api-docs/auth/approle) e [revoke-self](https://developer.hashicorp.com/vault/api-docs/auth/token#revoke-a-token-self).
