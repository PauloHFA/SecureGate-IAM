# ADR-053 — Coleta opcional do RabbitMQ

- Data: 21/10/2026
- Estado: aceita para implementação local 10B2b2a

## Contexto

A coleta IAM/enforcer não mostra backlog do broker. A 10B2b2 precisa tratar quatro dependências com contratos de acesso distintos. Dividir em 10B2b2a (RabbitMQ), 10B2b2b (PostgreSQL), 10B2b2c (Redis) e 10B2b2d (Vault) permite validar permissões e recuperação por serviço.

## Decisão

Usar rabbitmq_prometheus da imagem RabbitMQ 4.3.6 já adotada, habilitado somente com overlay Compose. Preservar management, definições/topologia e volume existentes. A configuração opcional conserva todas as linhas da configuração base e define porta interna 15692, interface interna 0.0.0.0 e métricas agregadas em /metrics. Publicação Docker fixa em 127.0.0.1, com porta externa configurável.

O endpoint local não exige autenticação. O Prometheus não recebe credenciais AMQP/administrativas. O isolamento depende do host e da rede Docker: containers com acesso à rede do broker também alcançam o listener. Esta decisão vale para desenvolvimento local; coleta remota e autenticação/TLS precisam de desenho próprio. Os outros endpoints nativos do plugin continuam disponíveis; a agregação limita o scrape configurado, não elimina todos os endpoints por objeto.

Job com intervalo de 15 s e caminho fixo /metrics; sem labels de filas, usuários ou payload acrescentados pelo projeto. Dashboard separado de seis painéis: up, ready, unacked, conexões, canais e consumidores. Script de migração modifica somente prometheus.yml, com backup e recusa de duplicidade/colisão entre portas de coleta; preserva credenciais e volumes e não executa Docker.

Smoke opt-in usa arquivos Compose versionados em workspace isolado e mensagens AMQP reais. Verifica ready/unacked/ACK, agregação sem nome da fila, consultas e frame Grafana, pausa/up=0/retomada e ausência de credenciais em artefatos públicos do teste. Não altera a stack persistente, aplicação Java, schema ou permissões de negócio.

## Consequências

O operador recria somente o container RabbitMQ para aplicar/remover o overlay, preservando volume; há interrupção do broker. Os totais incluem filas de retry/DLQ de todo o nó e não demonstram SLA de revogação, entrega fim a fim ou capacidade. PostgreSQL/Redis/Vault, logs/tracing e medição sustentada permanecem pendentes.

[Operação](../runbooks/metricas-rabbitmq-local.md), [evidências](../evidencias/etapa-10b2b2a.md), [inventário](../OBSERVABILIDADE.md).

Fonte primária consultada: [RabbitMQ Prometheus](https://www.rabbitmq.com/docs/prometheus), especialmente ativação, porta, autenticação opcional, agregação e intervalo. A documentação foi confrontada com a imagem real no smoke; a publicação loopback e a migração são decisões deste projeto.
