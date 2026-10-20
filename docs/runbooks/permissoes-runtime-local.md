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

`RuntimeDatabasePrivilegesIT` aplica V1–V17 com o proprietário em PostgreSQL 16.15 descartável, provisiona o SQL versionado e cria apenas no teste um login sintético membro do grupo. O IAM sobe com esse login e Flyway desativado. Os casos cobrem API administrativa, auditoria/Outbox atômicas, quarentena/histórico, retenção, concessões de coluna, idempotência e negação de operações privilegiadas. Senhas/tokens são sintéticos e não são versionados.

A V17 altera a execução do trigger, mas não reescreve o histórico. `database_actor` deve continuar identificando o login original. O grupo não recebe INSERT direto em `outbox_operations` nem acesso ao log particionado; ingestão e manutenção continuam nas identidades dedicadas da etapa 8.
