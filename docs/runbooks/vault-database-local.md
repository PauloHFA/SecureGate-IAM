# PostgreSQL temporário e migração separada — 9A2b

Executar na raiz com Java 21, Docker e PowerShell. Consultar [infraestrutura](../../infra/README.md), [perfil runtime](permissoes-runtime-local.md) e [ADR-036](../adr/036-credenciais-dinamicas-postgresql.md).

## Preparar

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Migrate-Database.ps1
.\infra\scripts\Provision-VaultDatabase.ps1
```

`verify` usa serviços descartáveis e não migra a stack persistente. `Migrate-Database.ps1` usa o JAR e a conta proprietária de `.env` em um processo administrativo curto para aplicar Flyway e o grupo `securegate_iam_runtime`. O processo termina sem servidor HTTP. O provisionador seguinte cria/reconcilia o emissor administrativo, configura o Database Engine e rotaciona sua senha no Vault. Esse provisionador não grava a nova senha em arquivo. Parar processos runtime antes de reaplicá-lo.

O novo AppRole `iam-runtime` e seu KV sem senha PostgreSQL são criados por `vault-init`. Os arquivos `infra/.local/iam-runtime-role-id` e `iam-runtime-secret-id` seguem a proteção e o TTL de SecretID local da [9A1](vault-kv-local.md). Não usar root como token da aplicação. A política do runtime não pode ler `secret/data/securegate/iam-core`.

## Iniciar o serviço

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableVaultDatabase -EnableBootstrap
```

O script ativa também a leitura KV, seleciona o AppRole runtime, omite credenciais PostgreSQL estáticas e variáveis de migração e desativa Flyway. Flags como `-EnableAuthorization`, `-EnableResourceEnforcement` e `-EnableOutboxPublisher` podem ser combinadas. A manutenção/ingestão de auditoria continua exigindo seu provisionamento e arquivos dedicados da etapa 8.

Verificar `http://127.0.0.1:8080/actuator/health/readiness`. O login PostgreSQL será emitido por processo e não é gravado em `.env`, configuração Spring ou arquivos locais. Não imprimir a resposta do endpoint de credenciais, o KV, heap ou configurações completas.

**A partir da 9A2c, token e lease são renovados automaticamente.** O padrão continua dez minutos, com margem de dois segundos e limite pelo token pai. Limites absolutos/falhas podem iniciar troca de geração. Se não houver renovação ou substituição confirmada, sessões são encerradas no prazo e novas conexões são recusadas. Após recuperar Vault/autenticação, o serviço pode reconstruir o pool sem reiniciar. Consultar o [guia de renovação e recuperação](vault-database-renovacao-local.md) para drenagem, métricas e atualização das políticas.

## Configuração direta

Para iniciar o JAR diretamente, definir `IAM_DATABASE_VAULT_ENABLED=true`, `IAM_VAULT_KV_ENABLED=true`, `SPRING_FLYWAY_ENABLED=false`, `IAM_DATABASE_URL`, `IAM_VAULT_ADDRESS` e os dois caminhos de arquivos AppRole runtime. O script configura esses valores. Não fornecer credencial de proprietário ao runtime. As novas URLs JDBC devem estar sem query/userinfo/fragmento; o modo documentado usa a rede local, não é o perfil de produção com TLS.

O comando administrativo aceita somente a invocação documentada `java -jar iam-core/target/iam-core-0.1.0-SNAPSHOT.jar --migrate-only`, com `IAM_MIGRATION_DATABASE_URL`, `IAM_MIGRATION_DATABASE_USERNAME` e `IAM_MIGRATION_DATABASE_PASSWORD` no ambiente desse processo. Não passar senha como argumento. O script de migração restaura as variáveis ao terminar.

## Falhas e recuperação

- `Dynamic database mode requires separate migrations.`: desativar Flyway somente no runtime e executar o migrador administrativo antes.
- `Vault database unavailable.`: verificar Vault, AppRole, política, configuração `database/roles/iam-runtime`, conexão administrativa do plugin e grupo runtime. A aplicação não usa senha local como alternativa.
- Falha entre PostgreSQL e configuração/rotação no Vault: com runtimes parados, corrigir a causa e reaplicar o provisionador. Ele gera uma nova senha inicial para reconciliar a conta dedicada. Não resetar a senha proprietária nem tentar adivinhar a senha rotacionada pelo Vault.
- Falha de revogação ao fechar: o pool local já está encerrado; investigar o contador administrativo e a saúde do Vault/Database Engine. TTL PostgreSQL impede nova autenticação após expiração, mas não encerra por si só uma sessão antiga. Verificar leases e sessões administrativamente; não usar force revoke para ocultar uma revogação que falhou no banco.
- Perda do Vault dev: seu estado é volátil; reaplicar `vault-init`, migrador/provisionamento quando necessário e iniciar novos processos. Chaves Transit perdidas não são recuperadas por esse procedimento.

## Validação

`VaultDatabaseIT` usa Vault 1.21.4 e PostgreSQL 16.15 reais, conectados por rede Docker descartável, políticas/SQL versionados e o JAR empacotado. Cobre migração repetida sem Tomcat, rotação do emissor, startup HTTP com credencial temporária, ACLs do AppRole, encerramento de sessões emprestadas e expiração. Testes de falha simulam respostas inválidas sem encaminhar segredos/causas externas. Consultar [evidências](../evidencias/etapa-09a2b.md).
