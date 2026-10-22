# ADR-044 — Snapshot KV no bootstrap do enforcer

- Estado: implementada localmente na 9A3; validação nas evidências.
- Data: 20/10/2026.

## Contexto

O IAM já lê KV no startup. O enforcer dependia de credenciais no ambiente, embora a infraestrutura já provisionasse AppRole e ACL exclusivos. Redis e RabbitMQ precisam receber configuração completa antes de criar os clientes.

## Decisão

Adicionar EnvironmentPostProcessor no próprio módulo, registrado em META-INF/spring.factories, sem depender do IAM. ENFORCER_VAULT_KV_ENABLED é opcional e estritamente booleano. AppRole é lido de arquivos com limites de tamanho/conteúdo; a aplicação não recebe token administrativo.

O endereço admite HTTPS ou HTTP em localhost/127.0.0.1, sem credenciais, query, fragmento ou subcaminho. Cada chamada tem timeout de 2 s. O caminho é fixo: secret/data/securegate/policy-enforcer. Exigir KV v2 com versão positiva, não destruída nem excluída e três valores textuais não vazios, limitados e sem caracteres de controle.

| Campo KV | Propriedade publicada |
| --- | --- |
| redis_password | spring.data.redis.password |
| rabbitmq_username | enforcer.consumer.username |
| rabbitmq_password | enforcer.consumer.password |

Ignorar outros campos, inclusive hosts existentes no KV, preservando configuração local de endereços/portas/vhost. Validar tudo e revogar o token AppRole antes de inserir o snapshot com precedência sobre ambiente, arquivos e argumentos. Falha de login/leitura/validação/revogação aborta o bootstrap sem publicar valores parciais nem usar fallback local. Exceções não contêm causa/resposta/caminho e toString da fonte é redigido.

## Consequências e limites

O snapshot vale até reiniciar. O token não permanece para refresh: atualizar KV não troca conexões automaticamente. Interrupção do Vault após o startup não apaga credenciais em memória. Falhas posteriores de Redis/RabbitMQ continuam sob retry/métricas dos workers; validar KV não comprova conectividade desses serviços.

Manter o adaptador no enforcer segue a 9A1 e evita dependência entre serviços ou refatoração do bootstrap do IAM. O modo anterior permanece padrão. O launcher combina -EnableConsumer e -EnableVaultKv e não repassa credenciais locais nesse modo. Não há migração nem rotação automática, e a conta local do broker continua compartilhada pelo bootstrap de desenvolvimento.

Ver [operação](../runbooks/enforcer-vault-kv-local.md) e [evidências](../evidencias/etapa-09a3.md).
