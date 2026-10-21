# ADR-055 — Coleta opcional Redis

- Data: 21/10/2026
- Estado: aceita para implementação local 10B2b2c

## Contexto

Redis guarda sessões e marcas de enforcement que não podem aparecer no scrape. O exporter suporta leitura de valores/nomes de chaves e logs de comandos; suas ACLs de exemplo são amplas para este caso. A infraestrutura base usa autenticação do usuário default com requirepass e AOF.

## Decisão

Adotar redis_exporter 1.95.0-alpine fixado por digest, com senha JSON montada read-only, usuário próprio securegate_metrics e publicação 127.0.0.1:19121. Configuração sem extração de CONFIG, checks de chaves/streams/grupos, CLIENT LIST, scripts ou módulos. Desabilitar exportação de valores, histogramas de latência e endpoint /scrape de alvos arbitrários. Scrape fixo /metrics a cada 15 s; timeout de conexão 2 s. Filesystem somente leitura, capabilities removidas e recursos limitados.

ACL dedicada: sem padrões de chaves/canais, -@all e somente +info, +ping, +client|setname, +command|info, +slowlog|len. GET/SET/SCAN/EVAL/CONFIG/ACL/FLUSHDB/SLOWLOG GET não são permitidos. O exporter tenta SLOWLOG GET incondicionalmente: a recusa é intencional, aparece em ACL LOG e elimina métricas do último comando lento, sem impedir INFO/estatísticas. Não ampliar a ACL para silenciar essa recusa nem habilitar debug. COMMAND INFO consulta apenas metadados; SLOWLOG LEN retorna contagem, sem argumentos.

Preparador administrativo verifica ACL ativa via redis-cli autenticado por variável temporária. Compara nomes de usuário/URI do mapa respeitando maiúsculas e minúsculas; recusa usuários extras, default diferente do contrato base e permissões/padrões/selectors inesperados de securegate_metrics. Só depois cria dois arquivos privados com CreateNew: mapa de senha CSPRNG e ACL de dois usuários com hashes SHA-256. Default mantém a mesma senha e permissões do Compose base; o usuário metrics recebe senha distinta. Arquivos existentes devem formar par válido e coincidir com a ACL calculada; não sobrescrever divergências.

Overlay substitui somente o comando Redis para --aclfile, preservando AOF/volume e healthcheck. Há reinício para aplicar ACL persistente. A autenticação da aplicação default permanece; CONFIG requirepass passa a não ser a fonte da senha, que é carregada do ACL file. Preparador não altera ACL ativa nem executa ACL LOAD/SAVE, CONFIG REWRITE ou reinício. ACL customizada precisa de integração própria; não substituir automaticamente usuários de terceiros.

Dashboard separado de seis painéis: up, redis_up, memória, clientes, chaves agregadas por INFO keyspace e comandos acumulados. Distinção entre scrape HTTP e conexão Redis; sem nomes/valores de chaves como labels. Os totais incluem administração/coleta, sem medir throughput IAM ou capacidade.

## Consequências

Senha do exporter permanece em arquivo privado, sem credencial administrativa em seu ambiente/URI. ACL e senha persistem por arquivo; não há rotação Vault dessa credencial. Endpoint HTTP e tráfego Redis local sem TLS; host/rede Docker e proteção dos arquivos permanecem limites. Alterações manuais de ACL em memória não persistem automaticamente e podem divergir do arquivo; recuperação/rotação exige operação explícita.

Smoke descartável verifica recusa de ACL customizada, de acesso a chaves/administrativo, dados AOF/autenticação após reinício, seis consultas/Grafana e falha de credencial com up HTTP disponível, seguida de recuperação. Não modifica a stack persistente ou aplicação Java. Vault 10B2b2d, logs/tracing 10B3 e medição sustentada continuam pendentes.

[Operação](../runbooks/metricas-redis-local.md), [evidências](../evidencias/etapa-10b2b2c.md), [inventário](../OBSERVABILIDADE.md).

Fontes primárias: [redis_exporter 1.95.0](https://github.com/oliver006/redis_exporter/tree/v1.95.0), [SLOWLOG na implementação](https://github.com/oliver006/redis_exporter/blob/v1.95.0/exporter/slowlog.go), [mapa de senha](https://github.com/oliver006/redis_exporter/blob/v1.95.0/contrib/sample-pwd-file.json) e [ACL Redis](https://redis.io/docs/latest/operate/oss_and_stack/management/security/acl/). Flags conferidas também no --help da imagem fixada; limitações verificadas com Redis 7.4.11.
