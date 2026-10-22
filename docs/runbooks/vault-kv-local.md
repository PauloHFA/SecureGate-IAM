# Segredos KV no startup local — 9A1

Executar na raiz do projeto, com Java 21 e Docker Desktop disponíveis. Consultar [infraestrutura](../../infra/README.md) e [ADR-034](../adr/034-bootstrap-kv-vault.md).

## Preparar e iniciar

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
.\infra\scripts\Test-Local.ps1
.\mvnw.cmd -B -ntp verify
.\infra\scripts\Start-IamCore.ps1 -EnableVaultKv
```

O script resolve as portas/URLs em `.env`, mas o IAM obtém usuário/senha PostgreSQL, senha Redis e usuário/senha RabbitMQ de `secret/data/securegate/iam-core`. O endereço do Vault é loopback; o caminho KV é fixo. Flags existentes, como `-EnableAuthorization` e `-EnableOutboxPublisher`, podem ser combinadas com `-EnableVaultKv`. Os pools de auditoria continuam exigindo o provisionamento e os arquivos dedicados documentados na etapa 8.

Verificar `http://127.0.0.1:8080/actuator/health/readiness`. A inicialização executa Flyway usando a credencial PostgreSQL do KV. Não imprimir valores de propriedades, respostas KV ou configuração completa do Compose.

## Configuração fora do script

| Variável | Padrão/uso |
| --- | --- |
| `IAM_VAULT_KV_ENABLED` | `false`; somente `true` ou `false` |
| `IAM_VAULT_ADDRESS` | `http://127.0.0.1:8200`; HTTPS fora de loopback |
| `IAM_VAULT_ROLE_ID_FILE` | `infra/.local/iam-core-role-id` |
| `IAM_VAULT_SECRET_ID_FILE` | `infra/.local/iam-core-secret-id` |
| `IAM_DATABASE_URL` | URL externa ao KV, referente ao mesmo banco das credenciais |

Entregar RoleID/SecretID por um processo administrativo fora da aplicação. No desenvolvimento, `vault-init` gera esses arquivos com `umask 077` e SecretID válido por 24 horas; restringir também a ACL NTFS da pasta no Windows. Não usar o token root na aplicação. A política existente permite somente o próprio KV e operações Transit específicas; `revoke-self` depende da política padrão do token. SecretID local é reutilizável, e produção exige entrega protegida e Vault persistente/TLS. Não alterar AppRole para SecretID de uso único enquanto bootstrap e Transit compartilharem os mesmos arquivos.

## Indisponibilidade e recuperação

Se login, leitura, validação ou revogação falhar, a inicialização termina com `Vault KV startup unavailable.` sem usar o segredo local como alternativa. Verificar saúde do Vault, endereço/TLS, existência e validade dos arquivos AppRole e política de leitura. Corrigir a causa e iniciar novamente. Não desativar a flag para contornar uma falha em um ambiente que exige KV.

O KV precisa conter os cinco campos listados na ADR. `vault-init` preserva o documento existente, portanto não corrige dados incompletos nem aplica rotação automaticamente. Uma atualização administrativa deve preservar os campos necessários e alinhar os valores com os serviços reais. Após atualizar uma senha estática no serviço e no KV, reiniciar o IAM de forma coordenada; não há atualização de conexões em execução nesta entrega.

Após perda do estado do Vault dev, executar novamente `vault-init`; isso também recria as chaves Transit, invalidando a verificação de tokens antigos. Não afirmar recuperação de dados criptografados com uma chave perdida.

## Validação isolada

O build padrão executa `VaultKvBootstrapIT` com Vault 1.21.4 e PostgreSQL 16.15 descartáveis. O teste sobe o IAM real, usa a política versionada, executa as migrações e consulta o banco com credenciais exclusivamente do KV, apesar de credenciais locais inválidas. Outro caso testa negação de ACL real. Os testes unitários cobrem falhas e publicação atômica do snapshot. A stack persistente não é usada pelos testes.
