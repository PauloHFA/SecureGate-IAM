# ADR-003 — Baseline da infraestrutura local

- Status: aceito como decisão de implementação da etapa 1.
- Data: 18/10/2026.

## Contexto

O PDF usa PostgreSQL 16, Redis 7 e RabbitMQ 3.13 como referências e não fixa Vault. A etapa 0 determinou verificar versões antes de criar o Compose. O desenvolvimento precisa de uma stack local reproduzível e independente dos serviços Java ainda não implementados.

## Decisão

Fixar `postgres:16.15-alpine`, `redis:7.4.11-alpine`, `rabbitmq:4.3.6-management-alpine` e `hashicorp/vault:1.21.4`. As tags foram confirmadas nos repositórios oficiais do Docker Hub durante a implementação. Os digests efetivamente usados serão registrados nas evidências; atualizar tags requer nova validação do smoke test.

RabbitMQ muda da referência 3.13 para 4.3.6. A topologia continua usando exchanges topic/direct, filas duráveis, TTL e dead-lettering. Não existe código consumidor ou banco RabbitMQ anterior neste projeto a migrar. A integração Spring AMQP será validada ao implementar os produtores e consumidores, sem pressupor compatibilidade apenas pela subida do broker.

Criar um projeto Compose `securegate-iam`, com portas publicadas apenas em loopback e volumes para PostgreSQL, Redis e RabbitMQ. Usar Vault dev em memória exclusivamente para desenvolvimento. Prometheus/Grafana ficam na etapa 10.

Gerar senhas aleatórias em `.env`, ignorado pelo Git. O script de preparação renderiza uma definição RabbitMQ local com hash salgado, usuário e permissões; o template versionado contém somente a topologia. Isso evita depender da criação de usuário padrão ao importar definições num nó vazio.

O bootstrap Vault é uma tarefa separada, dependente da saúde do Vault. Ele preserva uma chave existente, prepara KV/AppRole e guarda as credenciais de serviço em diretório ignorado. Usa políticas distintas para core, enforcer e rotação operacional.

## Consequências e limites

- Vault dev perde chaves ao reiniciar; a tarefa deve ser reexecutada e tokens antigos deixam de funcionar com a nova chave.
- As credenciais locais não são um mecanismo de entrega de segredos para produção.
- Filas classic em um único nó validam integração local, não alta disponibilidade ou tolerância a perda de nó.
- Retry usa exchange de retorno direta por fila para não multiplicar eventos na auditoria.
- O limite de tentativas pertence ao consumidor futuro; não há loop automático sem limite configurado no broker.
- Smoke tests verificam a topologia real e usam vhost temporário para mensagens, preservando filas de desenvolvimento.

## Fontes

- [Imagens PostgreSQL](https://hub.docker.com/_/postgres), [Redis](https://hub.docker.com/_/redis), [RabbitMQ](https://hub.docker.com/_/rabbitmq) e [Vault](https://hub.docker.com/r/hashicorp/vault).
- [Importação de definições RabbitMQ](https://www.rabbitmq.com/docs/definitions#import-on-boot-nuances).
- [Vault Transit](https://developer.hashicorp.com/vault/api-docs/secret/transit) e [AppRole](https://developer.hashicorp.com/vault/api-docs/auth/approle).
