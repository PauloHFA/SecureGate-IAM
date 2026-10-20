# ADR-041 — Recuperação da fila de limpeza PAM (9C2a)

## Contexto e divisão

A fila da 9C1 selecionava dez registros pelo instante de criação a cada ciclo. Dez leases com falhas persistentes podiam ocupar todos os lotes. A 9C2 foi dividida: **9C2a** trata a fila de credenciais e suas métricas; **9C2b** tratará a recuperação do observador de pedidos e os exercícios operacionais restantes. A 9C3 mantém o aceite da jornada central de auditoria.

## Decisão

Adicionar V21 com contador de tentativas de limpeza, próximo instante elegível e último código/instante de falha. O código persistido é exclusivamente CLEANUP_FAILED; não persistir resposta de backend, senha, token, usuário SQL ou texto de exceção nesses campos.

Após falha de um item, uma transação separada agenda intervalos de 2, 4, 8, 16, 32 e 60 segundos; as tentativas posteriores conservam o teto de 60 s. O contador satura em 30 sem abandonar o lease. A atualização compara o contador lido e aceita apenas estados ativos; uma resposta tardia não reabre FAILED/REVOKED. O próximo instante nunca retrocede. Falha do próprio agendamento mantém o item durável elegível para nova tentativa; o contador de ciclos com falhas continua sinalizando o problema.

Selecionar somente itens elegíveis, em lotes de até dez, ordenados pelo maior instante entre criação e próxima tentativa, com UUID para desempate. Isso permite avanço de outros itens durante o intervalo de um item problemático, sem prioridade permanente para entradas com valor inicial epoch. O teste cobre dez falhas seguidas e dois itens posteriores; não é garantia de vazão/SLA sob carga ilimitada.

Não há nova chamada ao emissor durante recuperação. Claims abandonados continuam consumidos e passam a FAILED com fato auditado. Leases conhecidos só recebem REVOKED na transação do fato VAULT_ACKNOWLEDGED após o RPC. Se a auditoria dessa confirmação falhar, o estado volta a REVOKING e o RPC pode ser repetido. Expiração lógica permanece independente conforme [ADR-040](040-auditoria-operacional-pam.md).

## Observabilidade

Consultar um agregado de banco a cada cinco segundos no mesmo executor de manutenção; coletar HTTP/Actuator não consulta o banco. Os gauges têm cardinalidade fixa, sem rótulos de identidade, pedido, lease ou correlação. Expor pending, ready, deferred, retrying, ambiguous e a idade desde o claim mais antigo entre itens pendentes. Os números são um snapshot local de uma fila compartilhada; não somar snapshots de réplicas como filas distintas.

Snapshot indisponível produz disponibilidade zero e valores NaN, preservando o horário do último sucesso. Um executor bloqueado pode manter o snapshot anterior; sua idade deve ser verificada. Falha de uma consulta não prova fila vazia, e backoff não aumenta a validade da credencial. O contador cleanup.failures representa ciclos com ao menos uma falha, não quantidade de itens.

## Limites e alternativas

Não excluir metadados, não colocar leases conhecidos em quarentena definitiva e não marcar limpeza como concluída por atingir limite de tentativas. FAILED sem lease conhecido permanece visível como resultado incerto; não é prova de que existe uma credencial órfã. A resolução administrativa desse incidente não deve falsificar o estado de auditoria para zerar métrica.

Não adicionar claim exclusivo para o RPC: instâncias podem repetir uma revogação idempotente; os fatos terminais continuam condicionais/transacionais. A comparação do contador evita atualizações concorrentes comuns; após saturação, o maior prazo prevalece. Não prometer chamada externa exatamente uma vez.

Perda do estado do Vault dev requer investigação de papéis/sessões PostgreSQL com identidade administrativa separada. Nem resposta de backend com estado perdido nem desaparecimento do lease comprovam ausência de sessões. Esta entrega não reconstrói leases, não faz limpeza SQL privilegiada automática e não elimina a limitação de sessões ativas além do TTL sob falha do Vault.

## Verificação e operação

Consultar [runbook](../runbooks/recuperacao-pam-local.md), [evidências](../evidencias/etapa-09c2a.md) e [guia de implementação](../GUIA-DE-IMPLEMENTACAO.md). Testes de banco real cobrem continuidade do lote, recriação dos objetos de serviço/repositório com agenda persistida, borda de elegibilidade, concorrência, estado terminal, rollback de auditoria e resultados incertos. Essa recriação não reinicia a JVM; o exercício de reinício completo permanece para a 9C2b.
