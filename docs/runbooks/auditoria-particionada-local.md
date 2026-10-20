# Base da auditoria particionada — 8A1

## Validar e atualizar

```powershell
.\mvnw.cmd -B -ntp clean verify
```

O build testa PostgreSQL descartável, inclusive upgrade V12 → V13 com auditoria/Outbox existentes. A stack persistente não é alterada por esses testes. No próximo startup do IAM atualizado, Flyway aplica a V13 de forma aditiva; não precisa limpar banco ou volumes.

`security_events` e sua FK com Outbox continuam operando. `event_log` é a área nova para ingestão do barramento e começa vazia. Nesta entrega não há consumidor de `audit.persist`, backfill automático, consulta pública ou mudança no caminho dos produtores.

## Manutenção automática opcional

Com JAR e infraestrutura atualizados:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableAuditPartitions
```

O switch controla somente o worker de partições; não ativa OAuth, publicador ou consumidor. A 8A2 passou a exigir a identidade dedicada: aplique V14 e siga o [provisionamento](identidades-auditoria-local.md) antes de ativar o switch. Não há fallback para a conexão principal.

V13 cria mês anterior, atual e três futuros em UTC. O worker mantém esse horizonte a cada hora e logo ao iniciar. Propriedades `iam.audit.partitions.months-ahead` (1–12, padrão 3) e `poll-millis` (1.000–86.400.000, padrão 3.600.000) limitam horizonte/frequência. Sem switch, só a criação inicial Flyway ocorre.

Métricas: `iam.audit.partitions.available`, `iam.audit.partitions.created` e `iam.audit.partitions.runs` com result success/unavailable. Available é o resultado do último tick, não um inventário de cobertura de todas as datas. Métricas não consultam banco durante scrape. Não expõem payload ou identificadores.

## Histórico e falhas

Para ingestão de um mês fora do horizonte, um operador autorizado pode provisionar explicitamente, por exemplo:

```sql
SELECT iam_audit.ensure_event_partitions(DATE '2024-01-01', 3);
```

A função é idempotente e aceita até 24 meses por chamada, com bounds UTC. Precisa de permissões do proprietário/operador; execução por PUBLIC é revogada. Não cria partições a partir de requisições de usuário e não usa DEFAULT. Relação existente com nome/bounds incompatíveis causa erro: investigue o catálogo antes de qualquer correção.

Mês ausente ou INSERT inválido não deixa recibo confirmado. Corrigida a causa, o mesmo evento pode ser reapresentado. EventId já registrado com timestamp/payload diferentes é conflito, não sucesso de deduplicação. Não apague recibos para contornar conflito.

UPDATE/DELETE/TRUNCATE são recusados no log, partições e recibos. A [8A2](identidades-auditoria-local.md) acrescenta logins restritos e pools dedicados. Proprietário/superusuário continua capaz de alterar DDL, e a conexão principal mantém esses privilégios; não há consumidor ligado nesta entrega.

## O que vem depois

8A2: identidades/pools dedicados, privilégios e timeouts implementados, conforme o [guia](identidades-auditoria-local.md). 8B: conectar consumidor com lote, confirmação após commit e recuperação. Consulta por cursor estável, retenção/arquivamento e planos/carga continuam posteriores.

Os recibos globais permanecem sem expurgo; não há política segura de detach/drop/backfill automático nesta entrega. Leia a [ADR-028](../adr/028-base-auditoria-particionada.md) e as [evidências](../evidencias/etapa-08a1.md).
