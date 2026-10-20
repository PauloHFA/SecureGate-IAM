# Perfil PostgreSQL de execução — 9A2a

Executar a partir da raiz do projeto com Java 21, Docker e a infraestrutura local preparada. Consulte a [ADR-035](../adr/035-permissoes-postgresql-runtime.md) para a matriz de permissões.

## Aplicar e provisionar

```powershell
.\mvnw.cmd -B -ntp verify
.\infra\scripts\Start-IamCore.ps1 -EnableVaultKv
```

A inicialização padrão ainda usa a conta proprietária e aplica Flyway, incluindo a V17. Após confirmar o startup, encerrar esse processo com Ctrl+C e provisionar o grupo:

```powershell
.\infra\scripts\Provision-RuntimeDatabase.ps1
```

O script usa a credencial administrativa local de `.env` para executar `infra/postgres/runtime-privileges.sql` no PostgreSQL do Compose. Restaura `PGPASSWORD` ao terminar e não imprime a senha. O efeito é criar/reconciliar o grupo `securegate_iam_runtime`, sem login ou senha. A operação é transacional e repetível; não modifica `.env`, KV, AppRole ou logins de auditoria.

**O IAM padrão ainda não passa a usar esse grupo.** Não colocar o nome NOLOGIN como usuário do DataSource. O login temporário, a configuração do Database Engine e a separação automatizada de migração/runtime serão integrados na 9A2b. Não criar um login permanente de produção para contornar essa sequência.

## Diagnóstico

- V17 ausente: executar as migrações como proprietário antes de reaplicar. O `verify` usa banco descartável e não migra a stack persistente.
- Grupo existente com login, atributo elevado, associação a outro papel ou propriedade de objetos: investigar administrativamente a origem e dependências antes de corrigir. O script recusa esse estado.
- Privilégio herdado inesperado: revisar concessões de PUBLIC e os pontos citados na ADR. O script não revoga PUBLIC para todo o banco.
- Permissão ausente após uma migração futura: revisar a lista explícita do SQL, testar e reaplicar; não conceder ALL nem tornar o runtime proprietário.

## Validação sem alterar a stack persistente

```powershell
.\mvnw.cmd -B -ntp clean verify
```

`RuntimeDatabasePrivilegesIT` aplica V1–V22 com o proprietário em PostgreSQL 16.15 descartável, provisiona o SQL versionado e cria apenas no teste um login sintético membro do grupo. O IAM sobe com esse login e Flyway desativado. Os casos cobrem API administrativa, auditoria/Outbox atômicas, quarentena/histórico, retenção, concessões de coluna, idempotência e negação de operações privilegiadas. Senhas/tokens são sintéticos e não são versionados.

A V17 altera a execução do trigger, mas não reescreve o histórico. `database_actor` deve continuar identificando o login original. O grupo não recebe INSERT direto em `outbox_operations` nem acesso ao log particionado; ingestão e manutenção continuam nas identidades dedicadas da etapa 8.

A V18 adiciona pedidos PAM. Reaplicar o perfil após migrar concede SELECT/INSERT e UPDATE somente de `revoked_at`; justificativa/prazos não podem ser alterados pelo runtime. Consultar o [guia 9B1](pedidos-privilegiados-local.md).

Após V20, reaplicar o perfil para permitir UPDATE dos marcadores expiration_recorded_at de pedidos/emissões PAM. A correlação de origem permanece sem UPDATE para o runtime; conferir o [guia 9C1](auditoria-pam-local.md).
## Concessões de recuperação PAM — V21

A V21 requer também UPDATE nas colunas cleanup_attempts, next_cleanup_at, last_cleanup_failure_at e last_cleanup_failure_code de privileged_issuances. O perfil verifica a existência das colunas antes de conceder, preservando o reprovisionamento de bases históricas. O runtime não recebe DELETE nessa tabela nem UPDATE da correlação de origem. A 9C2a testa o agendamento com a identidade runtime e a impossibilidade de reabrir estado terminal pela operação de retry; consultar [recuperação PAM](recuperacao-pam-local.md).

Na V22, reaplicar o perfil também permite UPDATE em observation_attempts, next_observation_at, last_observation_failure_at e last_observation_failure_code de privileged_requests. Origem, prazo e conteúdo do pedido continuam sem UPDATE. O teste runtime verifica agendamento condicional, leitura dos contadores/agregado e exclusão de pedido já observado da operação de retry. Consultar [ADR-042](../adr/042-recuperacao-do-observador-pam.md).
