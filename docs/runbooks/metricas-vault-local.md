# Métricas autenticadas Vault dev — 10B2b2d

## Preparação e reset planejado

Requisitos: Docker Desktop/Windows, PowerShell, .env preparado e coleta [10B2a](coleta-iam-local.md). Comandos na raiz. O Vault base em -dev expõe métricas anonimamente; o provisionador recusa essa configuração antes de emitir token. Não basta fornecer Bearer ao scraper para proteger esse listener.

**Aplicar o overlay recria Vault dev e apaga seu estado em memória.** Planejar reset com IAM/enforcer/coletores parados. Perdem-se chaves Transit/MFA, tokens, leases e AppRoles; podem ser afetados JWTs emitidos, dados MFA cifrados com chaves antigas e emissão/revogação de credenciais SQL. Este passo não preserva essas chaves. A entrega apenas preparou/testou os arquivos em projetos descartáveis; não aplicou reset à stack persistente do usuário.

Em instalação nova ou reset explicitamente planejado:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.vault-metrics.yml config --quiet
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.vault-metrics.yml up -d --no-deps --wait --wait-timeout 90 vault
docker compose --env-file .env -f infra/docker-compose.yml -f infra/docker-compose.vault-metrics.yml run --rm --no-deps vault-init
```

Refazer também [Vault Database](vault-database-local.md), [PAM](credenciais-pam-local.md) e outros provisionamentos usados no ambiente, conforme seus guias; o bootstrap básico não reconstitui todo o estado perdido. Não executar esses provisionamentos automaticamente sem revisar as credenciais SQL persistidas. Atualizar arquivos AppRole e reiniciar aplicações conforme a operação existente.

O overlay move o listener dev anônimo para 127.0.0.1:18200 dentro do container e mantém o endpoint publicado em VAULT_PORT (padrão 8200) com autenticação de métricas. Não publica portas internas 18200/18201. Incluí-lo nas futuras recriações do Vault; usar -p com o nome do projeto existente se diferente do padrão securegate-iam. O entrypoint carrega /vault/config automaticamente; não acrescentar -config para o mesmo HCL, pois duplicaria listeners.

## Emitir credencial e adicionar coleta

```powershell
.\infra\scripts\Provision-VaultMetrics.ps1
docker compose --env-file infra/.local/observability/compose.env -f infra/observability/docker-compose.yml stop
.\infra\scripts\Enable-VaultCollection.ps1 -VaultPort 8200
.\infra\scripts\Start-Observability.ps1
```

O provisionador usa somente o endereço loopback/VAULT_PORT e o root local de .env via header em memória; não passa valores em argumentos nem os imprime. Cria política própria [metrics.hcl](../../infra/vault/metrics.hcl) e token em infra/.local/vault-metrics-token. Política divergente não é sobrescrita. Token válido é reutilizado; arquivo com root/default/outro escopo é recusado, inclusive com -Rotate.

Contrato: service orphan, somente securegate-metrics, read em sys/metrics, TTL máximo de 1h, não renovável, sem default/lookup-self/identidade de aplicação e sem limite de usos dentro do TTL. Não permite KV, Transit, criação de tokens ou LIST. Proteção dos arquivos segue o sistema operacional; scripts não configuram ACL Windows. Não compartilhar token, .env, accessors ou saída completa de docker compose config.

O script de inclusão exige token/coleta preparados, usa VAULT_PORT informado e adiciona /v1/sys/metrics?format=prometheus com credentials_file. Cria prometheus.yml.before-vault e compose.env.before-vault, define OBS_VAULT=true e preserva flag enforcer/outros jobs/credenciais. Recusa duplicidade, backup existente e colisões com portas da coleta. Escritas são sequenciais, sem rollback automático em erro de disco. Start-Observability inclui o overlay de bind read-only; arquivo ausente é recusado, sem criar diretório como token.

## Verificar e interpretar

Sem Authorization, o endpoint publicado deve retornar 403; com token próprio, 200. Em Prometheus, up{job="vault"}=1. Grafana: **SecureGate Vault — Operação local**, UID securegate-vault, seis painéis:

| Consulta | Interpretação |
| --- | --- |
| up{job="vault"} | Scrape autenticado disponível; pode cair por token inválido/expirado. |
| vault_core_unsealed{job="vault"} | Estado unsealed do servidor, sem comprovar capacidade ou assinatura JWT. |
| sum(vault_core_handle_request_count{job="vault"}) | Contagem do timer nativo, incluindo scrape/administração. |
| sum(vault_barrier_get_count{job="vault"}) | Leituras internas da barreira; não é contador isolado de consultas KV de negócio. |
| sum(vault_token_create_count{job="vault"}) | Criação de tokens Vault, distintos de JWT OAuth. |
| sum(vault_token_lookup_count{job="vault"}) | Consultas a tokens Vault; inclui operações administrativas. |

Retenção do sink em memória 1 min, scrape 15 s; retenção Prometheus é independente. Séries podem aparecer somente após uma operação correspondente. Não interpretar ausência como zero. Contadores nativos reiniciam quando o processo reinicia; contagens de timers não são percentis/latência Transit ou throughput IAM. HTTP/host/rede local não têm TLS nesta entrega. Listener interno anônimo só é alcançável no container; acesso via Docker administrativo já permite controle do ambiente.

## Rotação e recuperação sem reiniciar Vault

Rotacionar antes de completar uma hora; sem renovação automática, up cairá após expiração. Com coletores parados:

```powershell
docker compose --env-file infra/.local/observability/compose.env -f infra/observability/docker-compose.yml stop
.\infra\scripts\Provision-VaultMetrics.ps1 -Rotate
docker compose --env-file infra/.local/observability/compose.env -f infra/observability/docker-compose.yml -f infra/observability/docker-compose.vault.yml up -d --no-deps --force-recreate --wait --wait-timeout 90 prometheus
.\infra\scripts\Start-Observability.ps1
```

Se OBS_ENFORCER=true, incluir também -f infra/observability/docker-compose.enforcer.yml no comando de recriação do Prometheus. Usar -p e -ProjectName correspondentes se a coleta tiver outro nome. Recriação atualiza o bind mount após substituição atômica do token; não apaga volumes ou senha Grafana.

Sem -Rotate, token expirado/revogado é recusado sem sobrescrita. Com -Rotate, o provisionador admite token não encontrado (lookup 400/403), emite outro limitado e substitui o arquivo. Para token anterior ainda válido, verifica o escopo e revoga seu accessor depois de instalar o novo. Se a instalação falhar, tenta revogar o novo token; falha de compensação pode deixá-lo válido até o TTL. Se a revogação antiga falhar após instalação, mensagem informa estado parcial: revisar accessors pelo administrador, sem publicar respostas. Política pode já ter sido criada mesmo que a emissão/disco falhe; não é operação transacional entre servidor e disco.

Rotação não reinicia Vault nem recria chaves; o smoke compara chave Transit, KV e política da aplicação antes/depois. Se Vault dev foi reiniciado, esse estado já foi perdido: rotação do token metrics não o recupera. Reavaliar o reset/bootstrap em vez de tratar falhas de JWT/MFA/leases como simples erro de scrape.

Para retirar só a coleta, parar coletores e restaurar os dois backups incorporando mudanças feitas depois da migração. A flag OBS_VAULT precisa ser retirada junto ao job. Revogar o token metrics usando administração e remover apenas seu arquivo após confirmar que não é mais usado. Não remover política default ou tokens da aplicação. Retirar o overlay do servidor recria Vault dev novamente e perde seu estado: preferir manter o listener protegido se o objetivo for apenas parar a coleta.

## Correlacionar as quatro dependências

Usar a mesma janela temporal e instância na coleta quando os jobs estiverem instalados. Esta matriz orienta diagnóstico; os smokes individuais não comprovam todos os componentes simultâneos sob carga.

| Sinal IAM/enforcer | Sinal da dependência | Leitura e limite |
| --- | --- | --- |
| Pool JDBC, erros e fila Outbox | PostgreSQL: conexões, commits, tamanho/deadlocks | Investigar saturação/erros; commits incluem coleta e outros clientes. |
| Enforcement e ACK do enforcer | Redis: redis_up, memória, comandos/chaves agregadas | Distinguir indisponibilidade de recusa; não identifica usuário/sessão por chave. |
| Publicações Outbox e mensagens do consumidor | RabbitMQ: ready/unacked/conexões/consumidores | Backlog/ACK do nó inclui retry/DLQ, não comprova processamento final de um evento. |
| iam.transit.requests e lifecycle de leases | Vault: up, unsealed, requests/token/barrier | Separar falha de scrape, autenticação, estado selado e API; não é medição de assinatura ou SLA. |

Não adicionar eventId, usuário, token, payload ou chave Redis como labels para obter correlação. Uma execução conjunta sustentada e inferências causais ficam para os ensaios 10C; logs/tracing 10B3 ainda serão implementados. Ver [inventário](../OBSERVABILIDADE.md) e os runbooks [PostgreSQL](metricas-postgres-local.md), [Redis](metricas-redis-local.md) e [RabbitMQ](metricas-rabbitmq-local.md).

## Validação opt-in

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=VaultObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Fixture descartável: recusa de listener dev anônimo, startup protegido antes do bootstrap, token mínimo/TTL/recusas, seis consultas/frame Grafana, erro de arquivo de token/403/recuperação, recuperação de token revogado e rotação válida com revogação antiga. Não reinicia o Vault depois de criar seus dados. Compara chave Transit/KV/política da aplicação e verifica artefatos sem credenciais; limpeza remove somente projetos gerados. [Resultados e limites](../evidencias/etapa-10b2b2d.md).
