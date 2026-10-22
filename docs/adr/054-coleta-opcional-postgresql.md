# ADR-054 — Coleta opcional PostgreSQL

- Data: 21/10/2026
- Estado: aceita para implementação local 10B2b2b

## Contexto

IAM/enforcer e RabbitMQ já possuem coleta local. Métricas do pool JDBC não mostram estatísticas do servidor PostgreSQL. A coleta deve funcionar sem fornecer a credencial administrativa ao exporter e sem consultar tabelas de negócio durante scrape.

## Decisão

Usar postgres_exporter 0.19.1 fixado por digest em overlay opcional da infraestrutura. O serviço alcança postgres:5432 na rede existente e publica somente 127.0.0.1:19187, porta externa configurável. O Compose base não inclui exporter. Serviço com filesystem somente leitura, sem capabilities adicionais e senha montada somente leitura; bind recusa arquivo ausente. Sem senha em URI, argumento de processo ou variável do container exporter: DATA_SOURCE_PASS_FILE aponta para arquivo privado.

Provisionar, por script administrativo opt-in, login securegate_metrics com pg_monitor, limite de três conexões, sem superuser/createdb/createrole/replication/bypassrls. Não conceder acesso às tabelas/schemas de negócio. Transação recusa papel existente com atributos, memberships, ownership ou acesso inesperado às tabelas/colunas de public/iam_* e CREATE no banco/schemas. Recusa não corrige ACLs silenciosamente. Password aleatório em arquivo ignorado; reexecução usa o mesmo arquivo e reaplica a credencial somente desse login. Variáveis temporárias transmitidas ao psql via docker exec -e por nome e restauradas no host.

pg_monitor concede estatísticas/configuração e funções de monitoração do cluster, incluindo visibilidade privilegiada de pg_stat_activity. Não é isolamento de dados por banco. PUBLIC continua como já configurado (CONNECT/TEMP/EXECUTE podem existir); default_transaction_read_only é um padrão de sessão, não uma barreira de autorização. O smoke desliga esse padrão antes de exigir recusas reais por ACL.

Desabilitar métricas legadas/settings e coletores por tabela, SQL, locks, roles, replication, vacuum e WAL; habilitar apenas database, stat_database e stat_bgwriter dentre os coletores nativos padrão da versão fixada. Sem descoberta automática nem consultas customizadas de negócio. Não habilitar pg_stat_statements ou exportação de texto SQL. Labels incluem nomes de banco e identidade técnica do servidor, sem labels adicionadas de usuário/evento/payload.

Job a cada 15 s, timeout SQL/exporter limitado e dashboard de seis painéis. Diferenciar up (HTTP scrape) de pg_up (conexão SQL): autenticação inválida pode produzir up=1 com pg_up=0. Script de inclusão preserva credenciais e configuração dos outros jobs, com backup e recusa de duplicidade/colisão.

## Consequências

Nova credencial local dedicada, com rotação explícita e sem expor administração no Prometheus. TLS/autenticação HTTP não configurados nesta coleta local; redes Docker/host continuam limite de acesso. Não altera migrations V22, grants dos logins IAM/auditoria/PAM ou aplicação Java. Não reconfigura automaticamente o banco persistente. Smoke descartável valida ACLs, transações, Grafana, falha de credencial SQL e recuperação; não mede capacidade nem prova correlação distribuída sob carga.

Próximas: Redis 10B2b2c, Vault 10B2b2d e logs/tracing 10B3. [Operação](../runbooks/metricas-postgres-local.md), [evidências](../evidencias/etapa-10b2b2b.md), [inventário](../OBSERVABILIDADE.md).

Referências primárias: [README postgres_exporter 0.19.1](https://github.com/prometheus-community/postgres_exporter/blob/v0.19.1/README.md), [coletores dessa versão](https://github.com/prometheus-community/postgres_exporter/tree/v0.19.1/collector) e [papéis predefinidos PostgreSQL 16](https://www.postgresql.org/docs/16/predefined-roles.html). Flags também conferidas no --help da imagem fixada.
