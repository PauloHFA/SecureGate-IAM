# ADR-042 — Recuperação do observador de pedidos PAM (9C2b)

## Contexto

O observador da 9C1 interrompia o lote de vinte pedidos na primeira falha de auditoria. A [9C2a](041-recuperacao-da-limpeza-pam.md) corrigiu a fila de credenciais, deixando a fila de pedidos e o exercício de reinício real nesta entrega.

## Decisão

Adicionar V22 com observation_attempts, next_observation_at, last_observation_failure_at e código fixo OBSERVATION_FAILED. Não armazenar mensagens de exceção, justificativa ou dados do backend nesses campos. As concessões runtime permitem apenas os novos campos operacionais; correlação original e conteúdo do pedido continuam sem UPDATE.

Processar cada item em sua transação de marcador/auditoria/Outbox. Em falha, desfazer essa transação, agendar retry em transação separada e continuar os demais itens. Ao terminar o lote, sinalizar que houve falha para o contador de ciclos. Falha ao persistir o agendamento não apaga o pedido; preserva elegibilidade e pode aumentar a frequência de tentativas enquanto o banco estiver indisponível.

Reutilizar a política operacional de intervalos 2, 4, 8, 16, 32 e 60 s, com teto de 60 s e contador saturado em 30. A atualização compara o contador e exige pedido vencido, não revogado e sem marcador. Nunca reabrir pedido já observado/revogado por retry, alterar expiresAt ou converter expiração lógica em revogação física.

Selecionar até vinte pedidos elegíveis, ordenados pelo maior instante entre vencimento e próxima tentativa, com UUID para desempate. A agenda persistida sobrevive à troca da JVM. Réplicas podem observar o mesmo item, mas o marcador condicional e a transação impedem duas transições confirmadas. Após saturação, o maior próximo instante prevalece em atualizações concorrentes; não há claim exclusivo ou promessa de processamento externo exatamente uma vez.

## Métricas

Sob iam.pam.expiration, expor pending, ready, deferred, retrying e oldest.overdue.seconds. A idade representa tempo desde o prazo mais antigo entre pedidos pendentes, diferente da idade desde o claim da limpeza de credenciais. A coleta a cada cinco segundos usa o executor do observador, sem consulta ao banco no scrape HTTP.

Disponibilidade zero e NaN indicam snapshot ausente/consulta falha; snapshot.last.success preserva o horário do último sucesso para detectar executor bloqueado/amostra antiga. audit.failures conta ciclos com ao menos uma falha, não itens nem total de falhas de banco. snapshot.failures conta falhas da consulta agregada. Gauges têm cardinalidade fixa e não devem ser somados entre réplicas da mesma fila.

## Aceite e limites

Testes com PostgreSQL cobrem lote problemático, retomada por novos objetos de serviço/repositório, fronteira de elegibilidade, atualização concorrente, saturação, exclusão de pedidos revogados/observados e concessões runtime. O teste de processo usa o JAR empacotado: injeta falha de INSERT de auditoria no banco descartável, confirma retry/rollback, encerra a JVM, remove a falha e inicia outra JVM no mesmo banco/Vault. Confere PIDs distintos, metadados de retry preservados, mesmo prazo/correlação e uma transição auditada com Outbox.

O pedido desse cenário é uma fixture SQL de teste, não um pedido criado pela API/autorização humana. A prova de reinício cobre o observador com emissão desabilitada; não comprova reinício durante entrega de senha ou revogação de lease real. A 9C3 mantém a jornada PAM HTTP/credencial/ingestão/consulta e seus exercícios integrados.

Não há retenção automática, reconciliador de órfãos após perda do Vault dev ou garantia de vazão/SLA. O acesso continua vencendo diretamente por expiresAt, mesmo quando a auditoria está atrasada. A 9C2 está implementada localmente por 9C2a/9C2b; a etapa 9 ainda depende de 9C3, KV do enforcer e hardening/aceite final.

Referências: [operação](../runbooks/recuperacao-pam-local.md), [evidências](../evidencias/etapa-09c2b.md), [guia passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
