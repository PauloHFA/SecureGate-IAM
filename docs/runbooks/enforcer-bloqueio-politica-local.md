# Consumidor de bloqueio e política — 7B

## Executar

Atualize os JARs com o build validado e inicie o IAM com a Outbox:

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Start-IamCore.ps1 -EnableAuthorization -EnableBootstrap -EnableOutboxPublisher
```

Em outra janela:

```powershell
.\infra\scripts\Start-PolicyEnforcer.ps1 -EnableConsumer
```

O mesmo switch agora habilita as filas `enforcer.revocation` e `enforcer.block-policy`, cada uma em seu worker/canal. Sem o switch, ambas ficam desligadas. Credenciais existentes continuam sendo lidas do `.env` por variável de ambiente; não gere novas credenciais nem remova volumes. O enforcer continua sem servidor HTTP.

## Exercitar eventos reais

- Crie/edite um usuário ou altere seu status pela [administração](provisionamento-local.md). A projeção recebe status e versão; um desbloqueio ACTIVE de versão maior supera o LOCKED anterior.
- Crie/edite uma política pelo [guia de políticas](politicas-local.md). O Redis registra versão, enabled e digest do alvo, sem copiar ou avaliar o documento de regras.
- Faça tentativas reais de login recusadas: conta conhecida gera contagem por userId/versão; IP numérico observado gera contagem por IP, inclusive para conta desconhecida.
- Login suspeito real produz sinal temporário por sid. Isso não cria bloqueio administrativo nem bloqueia IP por si só.

Confira no painel RabbitMQ o vhost configurado, a fila principal e `enforcer.block-policy.dlq`. `audit.persist` ainda não tem consumidor. Publicação confirmada e consumo são estados diferentes. O recurso pode consultar marcas com a ativação opcional da [7C1](recurso-enforcement-redis-local.md); não há medição de latência menor que dois segundos.

## Configuração

Propriedades com prefixo `enforcer.block-policy`:

| Propriedade | Padrão | Limite |
| --- | --- | --- |
| `window-seconds` | 300 | 1–3600 |
| `block-seconds` | 300 | 1–3600 |
| `risk-seconds` | 300 | 1–3600 |
| `account-threshold` | 5 | 1–1000 |
| `ip-threshold` | 20 | 1–1000 |

Para alterar, use o binding padrão do Spring por ambiente antes de iniciar; por exemplo `ENFORCER_BLOCKPOLICY_ACCOUNTTHRESHOLD`. Não passe credenciais na linha de comando. O lote/intervalo do consumidor usa as propriedades da [7A](enforcer-revogacao-local.md), com padrões de 20 mensagens e 500 ms. Alterar limiares não reconstrói o passado descartado; considere aquecimento. IP compartilhado/NAT exige calibrar limiar antes de usar o bloqueio na entrada HTTP.

## Projeções Redis

Todas as chaves começam com `securegate:{block-policy}:`. O sufixo do sujeito é SHA-256 em UTF-8, sem userId/IP em claro na chave.

| Categoria | Sujeito | Valor / prazo |
| --- | --- | --- |
| `identity` | userId | versão de 19 dígitos + status; sem TTL |
| `policy` | policyId | versão + enabled + digest do alvo; sem TTL |
| `risk` | sid | deadline Unix em ms; TTL até ocorrido + risk-seconds |
| `attempts:account` | userId:version | sorted set de attemptId/horário; no máximo account-threshold membros |
| `attempts:ip` | IP numérico canônico | sorted set limitado a ip-threshold membros |
| `blocked:account` | userId:version | deadline Unix em ms |
| `blocked:ip` | IP numérico canônico | deadline Unix em ms |
| `event` | eventId sem hash | fingerprint dos bytes; sete dias |

Os sorted sets são sinais de limiar, não contagem total para auditoria. Expiram após a janela mais 60 s; cada operação remove ocorrências fora da janela. O bloqueio temporário usa a tentativa mais recente do conjunto mais block-seconds. Atualização/replay não reduz prazo ativo nem cria novo prazo a partir do instante de consumo. Conta desconhecida sem IP pode concluir sem projeção de contador, preservando a ausência de evidência.

Watermarks de identidade/política não expiram para que um evento antigo não substitua estado mais recente após limpeza. Não delete esses valores para forçar desbloqueio. Use a alteração administrativa no IAM e acompanhe a versão nova. Same-version com valores contraditórios vai à DLQ, inclusive ACTIVE/LOCKED na mesma versão. Política enabled não representa uma decisão ALLOW.

## Recuperação

Falha Redis mantém NACK/requeue e encerra o lote; a próxima execução tenta novamente. Corrija tipos/valores incompatíveis pela investigação operacional e reconciliação com a fonte confiável, sem marcar conclusão manualmente. Mensagem inválida, tipo futuro não reconhecido, conflito de eventId ou versão contraditória vai à DLQ; investigue antes de replay. O encaminhamento classic local não demonstra tolerância a toda falha de dead-lettering.

Eventos mais de 60 s no futuro são rejeitados: verifique relógios e conteúdo de origem. Eventos atrasados podem atualizar estado versionado ainda mais novo, mas sinais temporários seguem seu horário original. Evite FLUSHDB/eviction: duplicatas podem repetir efeitos idempotentes, porém isso não recompõe todo estado perdido. O total de chaves de sujeitos/marcadores exige acompanhamento de memória.

Contadores internos: `iam.enforcer.block-policy.messages` (ACK/REJECT/RETRY) e `iam.enforcer.block-policy.connection.errors`. Não existe endpoint HTTP de métricas no enforcer. Métricas da Outbox continuam no [IAM](operacao-outbox-local.md).

Validação: [evidências 7B](../evidencias/etapa-07b.md). Decisões: [ADR-023](../adr/023-consumidor-bloqueio-politica.md). A [7C1](recurso-enforcement-redis-local.md) consulta essas projeções no recurso; a [7C2](autenticacao-enforcement-redis-local.md) inclui login/emissão/refresh.
