# KV no startup do enforcer — 9A3

Executar na raiz com Java 21 e Docker Desktop. Preparar conforme [infra/README](../../infra/README.md).

## Preparação e ativação

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
.\infra\scripts\Test-Local.ps1
.\mvnw.cmd -B -ntp verify
.\infra\scripts\Start-PolicyEnforcer.ps1 -EnableConsumer -EnableVaultKv
```

O bootstrap existente grava AppRole nos arquivos ignorados infra/.local/policy-enforcer-role-id e policy-enforcer-secret-id. O launcher verifica presença, usa Vault loopback na porta de .env e restaura o ambiente do processo ao encerrar. Sem -EnableVaultKv permanece o modo anterior. -EnableVaultKv sozinho valida KV mesmo com consumidor desativado; para consumir, combinar as duas flags.

## Contrato de configuração

| Variável | Padrão/efeito |
| --- | --- |
| ENFORCER_VAULT_KV_ENABLED | false; apenas true/false, sem espaços, ignorando caixa |
| ENFORCER_VAULT_ADDRESS | http://127.0.0.1:8200; launcher usa VAULT_PORT de .env |
| ENFORCER_VAULT_ROLE_ID_FILE | infra/.local/policy-enforcer-role-id |
| ENFORCER_VAULT_SECRET_ID_FILE | infra/.local/policy-enforcer-secret-id |

KV v2 fixo secret/data/securegate/policy-enforcer, ACL [policy-enforcer.hcl](../../infra/vault/policy-enforcer.hcl). redis_password e rabbitmq_username/rabbitmq_password substituem somente as três propriedades permitidas, inclusive com argumentos locais conflitantes. Hosts, portas e vhost continuam locais; campos extras são ignorados. O token de leitura é revogado antes de publicar o snapshot.

O launcher deixa as credenciais locais vazias com -EnableVaultKv. Não copiar tokens/senhas para argumentos, logs ou documentação. Proteger arquivos AppRole por permissões do sistema operacional. Produção exige entrega protegida desses arquivos e Vault persistente com TLS.

## Falhas e recuperação

1. Se o startup retornar `Vault KV startup unavailable.`, verificar disponibilidade/endereço, presença/permissões dos arquivos, validade do SecretID, ACL e versão ativa do KV. O erro é sanitizado sem resposta do Vault.
2. Corrigir o problema e reiniciar. Não há fallback local com a flag ativa; nenhum worker inicia se o bootstrap falhar.
3. Para SecretID expirado, executar vault-init novamente; ele reutiliza ou substitui os arquivos conforme validade. Nunca enviar esses arquivos ao Git.
4. Após perda do estado do Vault dev, executar vault-init e reiniciar processos afetados. A perda afeta também Transit/MFA; recriar chaves não recupera dados cifrados com chaves perdidas.
5. Para rotação, coordenar mudança no serviço e no KV e reiniciar o enforcer. vault-init só cria KV quando ausente; não sobrescreve segredo existente nem altera senhas de serviços persistidos.

Após o startup não há refresh nem consulta periódica ao Vault: o snapshot fica em memória. Indisponibilidade posterior de Redis/RabbitMQ é tratada pelos workers. A inicialização não é teste de saúde das dependências; acompanhar projeções e métricas existentes.

## Validação isolada

```powershell
.\mvnw.cmd -B -ntp -pl policy-enforcer -am verify
```

VaultKvBootstrapIT usa Vault, Redis e RabbitMQ descartáveis e credenciais geradas. Sobe EnforcerApplication pela descoberta real do postprocessor, com credenciais locais inválidas, publica revogação e confirma projeção Redis/ACK. Outro caso comprova negação de ACL real sem fallback. Testes unitários cobrem snapshot atômico, precedência, campos permitidos, revogação do token, configurações inválidas e falhas HTTP/KV. A stack persistente não é modificada.

Ver [ADR-044](../adr/044-bootstrap-kv-enforcer.md), [evidências](../evidencias/etapa-09a3.md) e [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
