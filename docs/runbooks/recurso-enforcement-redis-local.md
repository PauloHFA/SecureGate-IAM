# Recurso protegido com enforcement Redis — 7C1

## Ativar

Com infraestrutura local e JARs atualizados:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableOutboxPublisher -EnableResourceEnforcement
```

O novo switch também ativa Authorization/Transit. O script lê a senha/porta Redis existentes no `.env`, passa por ambiente e restaura os valores ao encerrar. Sem `-EnableResourceEnforcement`, a leitura distribuída fica desligada e o comportamento local anterior é preservado. Não gere novas credenciais nem limpe volumes/chaves para atualizar.

Em outra janela, inicie os consumidores:

```powershell
.\infra\scripts\Start-PolicyEnforcer.ps1 -EnableConsumer
```

Publicador, consumidor e leitor do recurso são ativações diferentes. Confira os [eventos](eventos-seguranca-local.md), o [consumidor de revogação](enforcer-revogacao-local.md) e o [consumidor de bloqueio/política](enforcer-bloqueio-politica-local.md). O recurso mantém o [fluxo local por operação](recursos-local.md), incluindo política e MFA: Redis não concede permissão quando falta política ou escopo.

## Comportamento de GET /api/resources/account

| Condição | Resultado |
| --- | --- |
| Token/grant inválido, jti/sid/família revogados | 401 invalid_token |
| Identidade Redis mais nova ou mesma versão LOCKED/DISABLED | 401 invalid_token |
| Conta/versão ou IP do par conectado bloqueados | 429 rate_limited |
| Watermark de política mais novo ou mesma versão disabled | 403 insufficient_scope |
| Redis indisponível, timeout, tipo/valor incompatível | 503 temporarily_unavailable |
| Sem marca Redis | Continua exigindo todas as verificações locais |

429 e 503 incluem `Retry-After: 5` e `Cache-Control: no-store`. O Retry-After orienta a repetição, não informa o prazo exato do bloqueio. IP vem da conexão ao servidor; forwarded headers não permitem escapar do bloqueio. O modo local continua sem suporte a origem confiável por reverse proxy.

A política é reavaliada no banco para cada operação. Versão antiga Redis não desfaz estado local mais novo. Marca enabled ou ausência de chave nunca substitui decisão positiva, escopo, prazo e MFA. Não remova uma marca para forçar acesso: altere a origem administrativa e acompanhe a projeção nova.

Nesta 7C1, login, emissão/refresh e introspecção continuam com suas verificações locais anteriores; não consultam esta porta de recurso. Os contadores de falha continuam sendo alimentados pelos eventos 7B, sem incrementar por GET. Risco Redis por sid não cria aprovação/MFA nem altera a avaliação local. A [7C2](autenticacao-enforcement-redis-local.md) estende o mesmo modo ao login/emissão/refresh e ao risco por sid; introspecção permanece com verificações locais.

## Configuração

| Propriedade `iam.resources.enforcement.` | Ambiente | Padrão |
| --- | --- | --- |
| enabled | IAM_RESOURCE_ENFORCEMENT_ENABLED | false |
| host | IAM_ENFORCEMENT_REDIS_HOST | 127.0.0.1 |
| port | IAM_ENFORCEMENT_REDIS_PORT | 6379 |
| password | IAM_ENFORCEMENT_REDIS_PASSWORD | Obrigatória se ativo |
| timeout-millis | IAM_RESOURCES_ENFORCEMENT_TIMEOUTMILLIS | 500; limite 100–2000 |

O script controla enabled/host/porta/senha a partir da stack. Timeout pode ser ajustado pelo binding Spring no ambiente antes da inicialização. É um limite por comando/conexão; a requisição faz várias consultas e ainda acessa PostgreSQL/Vault quando necessário.

Leituras de projeção não escrevem no Redis. O protocolo de chaves 7A/7B é compartilhado entre o IAM e o enforcer, com namespaces/hash slots e sujeitos em SHA-256. Chave com tipo incorreto ou valor malformado gera 503; não é tratada como ausência. SID aceita tombstone permanente `0`; jti/família/bloqueio temporário exigem deadline positivo. GET respeita o TTL Redis; o serviço não inventa um prazo de expiração novo.

## Diagnóstico e recuperação

Use as [métricas administrativas](operacao-outbox-local.md), com bootstrap ativo:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$settings = Read-LocalEnvironment (Join-Path (Get-Location) '.env')
$headers = @{ Authorization = 'Bearer ' + $settings.IAM_BOOTSTRAP_TOKEN }
Invoke-RestMethod -Headers $headers -Uri 'http://127.0.0.1:8080/actuator/metrics/iam.resources.enforcement'
```

Tags: operation token/request/policy; result allowed/denied/unavailable. São contadores locais e não contêm tokens/IP/identificadores. Allowed indica apenas que a leitura adicional não encontrou impedimento; a autorização final ainda depende do restante do fluxo.

Para 503, investigue conectividade, credencial, timeout e tipos/valores das chaves com acesso operacional autorizado, preservando conteúdo sensível. Recuperar o serviço permite tentar novamente, sem reiniciar o IAM ou alterar o token. Não substitua projeções por chaves vazias para contornar falha. A perda completa do Redis exige reconciliação: esta entrega não reconstitui todo histórico nem detecta automaticamente todas as ausências após perda/eviction.

O health agregado pode refletir Redis no modo ativo. A readiness existente continua baseada no banco/aplicação; o comportamento de falha do recurso é 503. Outros fluxos locais podem continuar funcionando nesta fase. Não há cache permissivo nem fallback para acesso bem-sucedido após falha da leitura.

Os testes usam Redis, PostgreSQL e Vault temporários e não interrompem a stack persistente. Eles aplicam projeções compatíveis com o protocolo diretamente no Redis; a medição da cadeia Outbox → RabbitMQ → enforcer → recurso continua na 7D. Veja [ADR-024](../adr/024-consulta-redis-no-recurso.md) e [evidências 7C1](../evidencias/etapa-07c1.md).
