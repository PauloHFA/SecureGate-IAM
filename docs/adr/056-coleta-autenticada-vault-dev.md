# ADR-056 — Coleta autenticada do Vault dev

- Data: 21/10/2026
- Estado: aceita para implementação local 10B2b2d

## Contexto

Vault 1.21.4 em -dev habilita métricas Prometheus e força acesso anônimo no primeiro listener. Um token colocado no scraper sem corrigir o listener não seria uma proteção efetiva. O teste inicial de acesso anônimo identificou esse comportamento; não considerar a configuração base autenticada para métricas.

## Decisão

Overlay opcional move o listener dev para 127.0.0.1:18200 **dentro do container** e acrescenta listener em 0.0.0.0:8200, com métricas autenticadas. Publicação no host continua somente 127.0.0.1:VAULT_PORT. Porta de cluster adicional explícita evita conflito interno; nenhuma porta 18200/18201 é publicada. Telemetria com retenção de 1 min e disable_hostname=true. A imagem já carrega /vault/config pelo entrypoint: não passar o mesmo arquivo novamente por -config.

Aplicar o overlay recria Vault dev e perde estado em memória: chaves, tokens, leases e AppRoles. Não aplicar automaticamente à stack persistente. Após reset planejado, refazer bootstrap e provisionamentos dependentes; JWTs antigos podem deixar de verificar e sessões PostgreSQL já abertas precisam de tratamento próprio. Não há migração de Vault dev para persistência nesta entrega. O smoke aplica o overlay antes de criar seus dados; testa preservação de Transit/KV/política da aplicação **durante provisionamento/rotação**, não durante o reset do Vault.

Política securegate-metrics concede apenas read em sys/metrics. Token service orphan, TTL/explicit_max_ttl de 1h, não renovável, ilimitado em usos dentro desse TTL e sem default/identidade de aplicação. Sem KV, Transit, token management, lookup-self ou LIST. Provisionador usa root local apenas por header em memória, verifica recusa anônima antes de criar política/token e recusa política/arquivo/token fora do contrato.

Reexecução preserva token válido. -Rotate permite substituir token revogado/expirado ou rotacionar um válido; confirma escopo do anterior quando ele existe, cria/verifica o novo, instala arquivo atomicamente e revoga o accessor anterior. Erro anterior à instalação tenta revogar o novo; se essa compensação falhar, token limitado pode permanecer até expirar. Falha ao revogar anterior depois da instalação é reportada como estado parcial. Sem resposta do servidor, revisar accessors administrativamente; não presumir transação distribuída entre Vault e disco. PowerShell 5.1 exige NullString explícito para File.Replace sem backup.

Prometheus usa Bearer credentials_file, overlay read-only e flag OBS_VAULT. Rotação exige coletores parados e recriação do Prometheus para renovar o bind mount; não depende de atualização de inode de um container existente. Script de inclusão cria backups de prometheus.yml/compose.env, preserva outros jobs/segredos e recusa duplicidade/colisão de portas. Dashboard separado de seis painéis, sem acrescentar labels de token/usuário/payload.

## Consequências

Endpoint publicado exige autenticação real e o scrape não recebe root. Token de uma hora exige rotação explícita: não há renovação automática ou gestão por AppRole. Listener dev anônimo permanece acessível apenas no loopback interno; host/rede publicada usa o listener protegido. TLS permanece fora desta operação local. Sem alteração do Compose base, aplicação Java, migrations ou políticas/grants existentes da aplicação.

As quatro dependências têm contratos individuais de coleta e uma matriz de correlação documentada; isso não comprova execução conjunta sustentada ou capacidade. Logs/tracing 10B3, medição 10C e ajustes 10D permanecem pendentes.

[Operação](../runbooks/metricas-vault-local.md), [evidências](../evidencias/etapa-10b2b2d.md), [inventário](../OBSERVABILIDADE.md).

Fontes primárias: [Vault server 1.21.4](https://github.com/hashicorp/vault/blob/v1.21.4/command/server.go), [telemetria](https://developer.hashicorp.com/vault/docs/configuration/telemetry), [API sys/metrics](https://developer.hashicorp.com/vault/api-docs/system/metrics) e [API de tokens](https://developer.hashicorp.com/vault/api-docs/auth/token). Listener/token mínimo e comportamento em PowerShell/Docker são verificados no smoke, sem inferir autenticação apenas da presença de um header.
