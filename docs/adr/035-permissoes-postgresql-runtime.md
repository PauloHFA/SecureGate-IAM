# ADR-035 — Permissões PostgreSQL para execução do IAM

- Data: 20/10/2026
- Estado: aceita para a entrega local 9A2a

## Contexto

O IAM principal ainda executa DML e Flyway com a conta proprietária/migradora. Antes de emitir logins temporários pelo Vault, é necessário definir e testar o conjunto de permissões que esses logins herdarão. Os pools dedicados de auditoria da etapa 8 permanecem independentes.

## Decisão

Criar o grupo PostgreSQL `securegate_iam_runtime` com `NOLOGIN`, sem privilégios de superusuário, criação de banco/papéis, replicação ou bypass de RLS; sem associação a outros papéis e sem propriedade de objetos. Provisionar por script administrativo separado do Flyway, após V17. Não criar senha nem login permanente nesta entrega.

| Área | Permissões do grupo |
| --- | --- |
| Tabelas de negócio existentes de Identity, Access, Policy, Authorization e Session | SELECT, INSERT, UPDATE, DELETE; lista explícita |
| `iam_audit.security_events` | SELECT e INSERT |
| `iam_audit.outbox_events` | SELECT, INSERT, DELETE e UPDATE somente dos sete campos de entrega |
| `iam_audit.outbox_operations` | Somente SELECT |
| Log particionado, recibos, catálogo/arquivo de auditoria, histórico Flyway | Sem acesso de dados pelo grupo |
| Schemas dos contextos | USAGE onde necessário; sem CREATE |
| Manutenção, arquivamento e função administrativa de quarentena | Sem EXECUTE |

Os sete campos atualizáveis são `published_at`, `attempts`, `next_attempt_at`, `claim_id`, `lease_until`, `last_error_code` e `quarantined_at`. O runtime precisa de DELETE na Outbox para retenção; o recorte de publicados é garantido pelo caso de uso, não por RLS. O perfil também não impõe autorização por linha nas tabelas de negócio. Não conceder TRUNCATE, REFERENCES, TRIGGER ou grant option.

A V17 substitui a função do trigger de histórico da quarentena por `SECURITY DEFINER`, com `search_path=pg_catalog`, alvo qualificado e EXECUTE revogado de PUBLIC. Isso permite registrar a transição sem INSERT direto ou USAGE da sequência de histórico pelo runtime. `database_actor` continua usando `session_user`, preservando a identidade do login mesmo com execução elevada da função. Migrações anteriores permanecem intactas.

O provisionamento roda em transação, rejeita atributos/associações/propriedade inesperados e substitui concessões diretas do grupo nos schemas conhecidos. Revoga também ACLs de coluna anteriores antes de reaplicar a lista permitida; revogar somente a ACL da tabela não basta. Permissões indevidas herdadas de PUBLIC nos pontos verificados interrompem a operação, sem alterar PUBLIC globalmente. Novas tabelas não recebem concessões automaticamente: migração futura precisa revisar este perfil e reaplicar o provisionamento.

## Limites e sequência

A 9A2a fornece o grupo e comprova o IAM real sob esse perfil; não muda a conta padrão da aplicação nem provisiona o Database Engine do Vault. O teste aplica migrações como proprietário e inicia o IAM como um login sintético membro do grupo, com Flyway desativado. A separação automática de migração/runtime e o login temporário do serviço dependem da **9A2b**. Renovação de tokens/leases, tratamento de expiração/revogação e troca segura do pool ficam na **9A2c**.

Um login deve ter apenas esse grupo, atributos restritos e nenhuma propriedade ou concessão adicional; isso será imposto pelo emissor da 9A2b. Grupos PostgreSQL são globais ao cluster. O script não remove direitos de outros papéis nem audita todo o cluster: PUBLIC, catálogos, funções padrão e TEMP do banco podem fornecer permissões usuais adicionais. A negação de DDL é comprovada nos schemas e objetos persistentes da aplicação; não se declara bloqueio de tabelas temporárias ou invisibilidade de metadados. Administração/superusuário continua fora desse limite.

Fontes: [privilégios PostgreSQL 16](https://www.postgresql.org/docs/16/ddl-priv.html), [segurança de funções](https://www.postgresql.org/docs/16/sql-createfunction.html#SQL-CREATEFUNCTION-SECURITY) e [Database Engine Vault, para a próxima entrega](https://developer.hashicorp.com/vault/api-docs/secret/databases).
