# Infraestrutura local — etapa 1

Docker Compose para PostgreSQL, Redis, RabbitMQ e Vault, com inicialização das filas, KV, Transit e AppRoles. Os serviços da aplicação ainda não fazem parte deste Compose.

## Preparação e execução

Requisitos: Docker Engine com containers Linux, Compose com suporte a `--wait` e PowerShell 5.1+ ou PowerShell 7. Os comandos partem da raiz do repositório. A preparação e o smoke test são scripts PowerShell; não é preciso executar Maven para subir a infraestrutura.

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml config --quiet
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\infra\scripts\Test-Local.ps1
```

Em Linux/macOS com PowerShell 7, use `pwsh -File ./infra/scripts/Initialize-Local.ps1` e `pwsh -File ./infra/scripts/Test-Local.ps1`. A execução desses scripts foi validada no Windows; outras plataformas ainda precisam de validação.

O primeiro script cria `.env` com credenciais aleatórias, preserva um `.env` existente e gera `infra/.local/rabbitmq-definitions.json` com hash de senha. O arquivo versionado `rabbitmq/definitions.json` contém apenas a topologia. Não copie os placeholders de `.env.example` como senhas.

Usar `--env-file .env` explicitamente evita depender do diretório que o Compose escolheria para procurar suas variáveis. O `.env` usa linhas simples `CHAVE=valor`, sem aspas, expansão ou comentários ao final dos valores; esse é o formato aceito pelos scripts. As senhas geradas são hexadecimais.

Se uma porta estiver ocupada, ajuste a variável correspondente em `.env` antes de subir. Não altere as senhas depois de criar os volumes esperando que os bancos persistidos sejam atualizados: rotação precisa ser aplicada nos serviços e no Vault de forma coordenada.

## Serviços e portas

| Serviço | Imagem fixada | Endpoint no host |
| --- | --- | --- |
| PostgreSQL | `postgres:16.15-alpine` | `127.0.0.1:15432` |
| Redis | `redis:7.4.11-alpine` | `127.0.0.1:6379` |
| RabbitMQ | `rabbitmq:4.3.6-management-alpine` | AMQP `127.0.0.1:5672`, painel `http://127.0.0.1:15672` |
| Vault dev | `hashicorp/vault:1.21.4` | `http://127.0.0.1:8200` |
| vault-init | mesma imagem do Vault | tarefa que termina com código 0 |

Todas as portas publicadas ficam em loopback. Entre containers, usar os nomes DNS `postgres`, `redis`, `rabbitmq` e `vault`, com suas portas internas. Não usar `127.0.0.1` para comunicação entre containers.

As credenciais do painel RabbitMQ ficam em `.env` (`RABBITMQ_USER` e `RABBITMQ_PASSWORD`). O acesso administrativo local ao Vault usa `VAULT_DEV_ROOT_TOKEN_ID`. Esses valores nunca são impressos pelos scripts. Use um editor local para consultá-los quando necessário e não compartilhe o arquivo ou a saída completa de `docker compose config`.

## Persistência

PostgreSQL, Redis com AOF e RabbitMQ usam volumes nomeados. `docker compose down` preserva esses dados.

**Vault dev mantém seu estado em memória. Reiniciar o container apaga chaves, segredos e AppRoles.** Após reiniciar, execute novamente a tarefa de inicialização. Isso cria uma nova chave quando o estado anterior foi perdido; portanto, tokens assinados antes da perda não podem ser verificados com a nova chave.

```powershell
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
```

O bootstrap não recria nem rotaciona uma chave existente e rejeita configuração MFA incompatível com AES-256-GCM não exportável/sem backup em texto puro. Reutiliza SecretIDs válidos; se expirarem (24 horas) ou se o Vault perder seu estado, atualiza os arquivos locais de AppRole. Rotação é uma operação explícita, separada das permissões da aplicação.

## RabbitMQ: topologia

Vhost padrão: `securegate`. Os recursos são duráveis. O usuário administrativo local é incluído nas definições geradas, porque importar definições em um nó vazio impede a criação automática do usuário/vhost padrão.

| Fila de trabalho | Bindings em `security.events` |
| --- | --- |
| `enforcer.block-policy` | `auth.login.suspicious`, `auth.login.failed`, `user.locked`, `policy.changed`, `identity.user.*` |
| `enforcer.revocation` | `token.revoked`, `session.terminated` |
| `audit.persist` | `#` |

O [publicador da 6B](../docs/runbooks/publicacao-outbox-local.md) empacota essa mesma definição sem credenciais e declara a topologia ao conectar para enviar. Os dois bindings novos de falha de login/evolução de identidade preparam o consumidor da etapa 7.

A [operação da Outbox na 6C](../docs/runbooks/operacao-outbox-local.md) inclui métricas autenticadas, quarentena de falhas permanentes e limpeza opcional de publicações antigas (`-EnableOutboxRetention`). Não remove auditoria, pendências ou mensagens do broker.

O [consumidor 7A](../docs/runbooks/enforcer-revogacao-local.md) pode ser iniciado em outro processo com `Start-PolicyEnforcer.ps1 -EnableConsumer`. Consome `enforcer.revocation` e, com a [7B](../docs/runbooks/enforcer-bloqueio-politica-local.md), também `enforcer.block-policy`; grava efeitos/deduplicação no Redis e confirma depois. A [7C1](../docs/runbooks/recurso-enforcement-redis-local.md) permite ao recurso do IAM consultar essas marcas com `Start-IamCore.ps1 -EnableResourceEnforcement`; a [7C2](../docs/runbooks/autenticacao-enforcement-redis-local.md) estende o mesmo modo ao login e à emissão/refresh humanos. Não limpe dados Redis para atualizar o serviço.

Cada fila tem uma `.dlq` e uma `.retry`:

- Rejeição sem requeue envia para `security.events.dlx`, com routing key igual ao nome da fila original, e então para sua `.dlq`.
- Para erro recuperável, o consumidor futuro publica em `security.events.retry`, usando o nome da fila original como routing key.
- A fila `.retry` aguarda 5 segundos e envia via `security.events.redelivery` diretamente à fila original. Isso evita replicar cada tentativa para a auditoria ou para outros consumidores.
- A política de tentativas pertence ao consumidor. A 7A mantém revogações em requeue durante falhas transitórias e rejeita mensagens inválidas à DLQ. Consumidores que usarem `.retry` deverão confirmar a nova publicação antes de confirmar a mensagem original e definir seu limite de tentativas.

As filas locais são classic em um único nó; isso não demonstra alta disponibilidade nem garante entrega sem perdas em todas as falhas de dead-lettering. A validação de resiliência da aplicação permanece nas etapas 6/7.

## Vault: recursos e permissões

| Recurso | Configuração |
| --- | --- |
| KV v2 | `secret/securegate/iam-core` e `secret/securegate/policy-enforcer` |
| Transit | `transit/keys/securegate-jwt`, RSA 3072, não exportável, sem backup em texto puro |
| Transit MFA | `transit/keys/securegate-mfa`, AES-256-GCM, não exportável, sem backup em texto puro |
| AppRole `iam-core` | Lê seu KV, lê a chave pública RSA, assina e cifra/decifra com a chave MFA; não lê/exporta a chave AES |
| AppRole `policy-enforcer` | Lê apenas seu KV; não pode assinar nem cifrar/decifrar MFA |
| Política `securegate-key-operator` | Permite ler metadados e rotacionar as chaves RSA/MFA; não é atribuída às aplicações |

Os tokens de aplicação têm TTL de 1 hora e máximo de 4 horas. Os SecretIDs têm TTL de 24 horas e são reutilizáveis no ambiente local. RoleID e SecretID ficam em `infra/.local/<serviço>-role-id` e `<serviço>-secret-id`, ignorados pelo Git. O script aplica `umask 077`; no Windows, a proteção efetiva também depende das permissões NTFS da pasta.

O `iam-core` pode ler as credenciais KV no startup com `Start-IamCore.ps1 -EnableVaultKv` (9A1); consulte o [guia de operação](../docs/runbooks/vault-kv-local.md). O enforcer ainda recebe credenciais pelo ambiente. As contas principais de banco/broker continuam sendo contas de bootstrap local; credenciais dinâmicas, renovação e separação migração/runtime ficam para a 9A2. Os pools de auditoria da etapa 8 preservam suas contas dedicadas por arquivo.

A 9A2a prepara o grupo NOLOGIN de execução do IAM com `Provision-RuntimeDatabase.ps1`, após aplicar V17. Não cria senha/login nem muda a conexão padrão. Consulte o [guia de permissões runtime](../docs/runbooks/permissoes-runtime-local.md); a emissão temporária pelo Vault será integrada na 9A2b.

A 9A2b adiciona AppRole/KV `iam-runtime`, sem credencial proprietária, e o modo `Start-IamCore.ps1 -EnableVaultDatabase`. Preparar o banco em processo separado com `Migrate-Database.ps1` e configurar o emissor/Database Engine com `Provision-VaultDatabase.ps1`; consulte o [guia](../docs/runbooks/vault-database-local.md). A 9A2c acrescenta renovação, troca e recuperação do pool; reaplicar `vault-init` e o provisionador para atualizar ACL e revogação SQL, com runtimes parados. Consultar o [guia operacional](../docs/runbooks/vault-database-renovacao-local.md). A configuração Database não é criada automaticamente por `vault-init`.

Produção exige Vault persistente, TLS, unseal, recuperação e entrega protegida das credenciais iniciais. O modo dev pode registrar o token administrativo no log de inicialização; não compartilhe esses logs. A tarefa `vault-init` não imprime segredos.

## Validação e diagnóstico

O smoke test verifica consulta PostgreSQL por TCP com senha, operações Redis autenticadas, exchanges/filas/bindings efetivos, roteamento, DLQ, retry com TTL, login AppRole, KV, assinatura Transit e cifra/decifra MFA com dados sintéticos. Testa também negações de rotação, exportação, acesso MFA pelo enforcer e acesso a segredo alheio. Ele revoga os tokens de teste ao terminar. Para uma stack existente, reaplique `vault-init` antes deste teste. Consulte o [guia 5C1](../docs/runbooks/mfa-base-local.md).

Os testes RabbitMQ de mensagens ocorrem em um vhost temporário com a mesma topologia e são removidos ao final, sem consumir filas de desenvolvimento. Se o processo for encerrado abruptamente, um vhost `securegate-smoke-*` pode restar; confira o nome antes de removê-lo no painel.

```powershell
docker compose --env-file .env -f infra/docker-compose.yml ps -a
docker compose --env-file .env -f infra/docker-compose.yml logs --tail 80 postgres redis rabbitmq vault-init
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
.\infra\scripts\Test-Local.ps1
```

`vault-init` deve terminar com código 0; ele não é um serviço continuamente saudável. Se o Compose falhar com caminho de bind mount, rode `Initialize-Local.ps1` antes da subida. Se `.env` e os dados persistidos divergirem, restaure as credenciais anteriores ou faça uma rotação explícita, sem apagar volumes para tentar corrigir.

## Parada

```powershell
docker compose --env-file .env -f infra/docker-compose.yml stop
docker compose --env-file .env -f infra/docker-compose.yml down
```

O primeiro para containers existentes; o segundo remove containers/rede e mantém volumes. Não usar `down -v` na rotina: ele remove os dados persistidos.

## Referências

- [Definições RabbitMQ e importação no boot](https://www.rabbitmq.com/docs/definitions).
- [Dead-letter exchanges](https://www.rabbitmq.com/docs/dlx).
- [Vault Transit API](https://developer.hashicorp.com/vault/api-docs/secret/transit).
- [Vault AppRole API](https://developer.hashicorp.com/vault/api-docs/auth/approle).
- [ADR-003: baseline da infraestrutura](../docs/adr/003-infraestrutura-local.md).

O switch `-EnableAuditPartitions` de `Start-IamCore.ps1` habilita somente a manutenção opcional da [área de ingestão particionada 8A1](../docs/runbooks/auditoria-particionada-local.md). A V13 preserva auditoria produtora/Outbox. A [8A2](../docs/runbooks/identidades-auditoria-local.md) exige provisionar logins dedicados com `Provision-AuditDatabase.ps1` após V14. `-EnableAuditIngestion` disponibiliza o store restrito, sem iniciar consumidor.

O consumidor da **8B** é opcional: `Start-IamCore.ps1 -EnableAuditConsumer -EnableAuditPartitions` usa as credenciais dedicadas provisionadas na 8A2. Acrescente `-EnableOutboxPublisher` para publicar novos eventos localmente. Consulte o [guia de consumo em lote](../docs/runbooks/consumidor-auditoria-local.md); não há ativação automática ou alteração da stack persistente pelo build.

A consulta da **8C** usa `Start-IamCore.ps1 -EnableBootstrap -EnableAuditQuery`, sem iniciar consumidor/publicador. V15 adiciona índices de ator/correlação e a API exige janela limitada e cursor; consulte o [guia de consulta](../docs/runbooks/consulta-auditoria-local.md).

Após V16, reaplique `Provision-AuditDatabase.ps1` para conceder o verificador de replay à ingestão. A **8D1** usa `Archive-AuditMonth.ps1 -Month yyyy-MM -HotMonths 6` em modo de plano; `-Apply` arquiva explicitamente um mês elegível no mesmo banco, sem apagar recibos/dados. Consulte o [guia de arquivo](../docs/runbooks/arquivamento-auditoria-local.md).

A **8D2** usa `Measure-Audit.ps1` para benchmark optativo com PostgreSQL descartável, limitado a quatro CPUs/4 GiB, e JVM de 1 GiB. O padrão é dois milhões de eventos mais amostras; a execução não lê `.env` nem acessa a stack persistente. Consulte o [guia de medição](../docs/runbooks/medicao-auditoria-local.md) e preserve relatórios antes de `clean`.

A 9B1 oferece `Start-IamCore.ps1 -EnablePam`, que ativa Authorization/Transit e registra pedidos com MFA e TTL. Aplicar V18 e reaplicar o perfil runtime antes do modo dinâmico; ver o [guia PAM](../docs/runbooks/pedidos-privilegiados-local.md). Esta fase não emite credenciais humanas no Vault.

A 9B2 usa `Provision-PamDatabase.ps1` após V19 e `Start-IamCore.ps1 -EnablePamCredentials`. O bootstrap entrega AppRole iam-pam separado; o provisionador configura mount/emissor exclusivos e visão de diagnóstico. Consulte o [guia de credenciais PAM](../docs/runbooks/credenciais-pam-local.md), incluindo o limite de sessões já abertas durante falhas do Vault.

A 9C1 exige V20 e reaplicação das concessões com processos PAM parados. `-EnablePam` observa expiração de pedidos; a emissão observa também o prazo das credenciais e preserva a correlação original nos fatos. Consulte o [guia de auditoria PAM](../docs/runbooks/auditoria-pam-local.md); expiração lógica não confirma revogação pelo Vault.
