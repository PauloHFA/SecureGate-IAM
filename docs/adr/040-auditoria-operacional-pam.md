# ADR-040 — Fatos operacionais PAM

- Data: 20/10/2026
- Estado: aceita para a entrega local 9C1

## Decisão e escopo

Implementar a primeira divisão da 9C com fatos distinguíveis de autorização, emissão confirmada no IAM, observação de expiração lógica, falha e confirmação de revogação pelo Vault. Completar o contrato de auditoria PAM sem declarar concluídas a recuperação/observabilidade 9C2 nem a jornada de ingestão/consulta 9C3.

V20 adiciona origin_correlation_id e expiration_recorded_at a pedidos/emissões. A correlação de criação do pedido e a da chamada de emissão são independentes; cada manutenção preserva a origem correspondente. Para linhas anteriores, origem nula usa o UUID do pedido como referência estável; não se inventa a correlação histórica perdida. As colunas são aditivas e a origem é imutável pelas concessões runtime após INSERT.

## Contrato dos fatos

| Evento | Significado | Dados específicos |
| --- | --- | --- |
| pam.request.authorized | Pedido autorizado e persistido; não é uma senha. | requestId, expiresAt. |
| pam.request.revoked | Revogação lógica do pedido; não confirma remoção do login. | requestId; expiresAt quando produzido pelo fluxo próprio. |
| pam.request.expired | Prazo do pedido observado, sem revogação lógica anterior. | requestId, expiresAt, meaning=LOGICAL_DEADLINE. |
| pam.credential.issued | Lease registrado e emissão aprovada antes da resposta; não comprova recebimento pelo cliente. | requestId, expiresAt, meaning=ISSUANCE_COMMITTED. |
| pam.credential.failed | Emissão não concluída/entregue por esse fluxo; lease conhecido é encaminhado à limpeza. | requestId, reason=ISSUANCE_AMBIGUOUS, DELIVERY_ABORTED ou CLAIM_ABANDONED. |
| pam.credential.expired | Orçamento de validade da credencial observado, independentemente da resposta do Vault. | requestId, expiresAt, meaning=LOGICAL_DEADLINE. |
| pam.credential.revoked | Vault confirmou o RPC de revogação síncrona; não é inferido apenas do relógio ou da resposta 202 administrativa. | requestId, meaning=VAULT_ACKNOWLEDGED. |
| pam.access.denied | Recusa nos pontos de autorização PAM ou consumo/confirmação da emissão, vinculada a identidade verificada. | operation, reason e stage, todos códigos de servidor. |

O envelope existente mantém eventId/version/source, ator, recurso fixo, correlação e horário. Acrescentar campos aos eventos existentes é uma evolução aditiva; registros antigos continuam consultáveis com seus campos originais. Consumidores de auditoria continuam usando o contrato genérico.

Nenhum desses dados inclui token, senha, semente MFA, username, lease ID, justificativa ou mensagem de exceção. Os códigos de recusa são ACCESS_DENIED, MFA_REQUIRED, RATE_LIMITED, REQUEST_CONFLICT e REQUEST_UNAVAILABLE. Stages: AUTHORIZATION, CLAIM e CONFIRMATION. Uma string de erro desconhecida vira ACCESS_DENIED, não conteúdo livre de evento.

## Atomicidade e repetição

Observadores consultam lotes limitados. O pedido usa até vinte itens por ciclo de 500 ms; a emissão preserva o worker da 9B2. UPDATE condicional marca expiration_recorded_at e grava auditoria/Outbox na mesma transação. Falha desfaz marcador e evento; repetição/reinício/concorrência pode tentar novamente, mas apenas a transação que obtém o marcador grava o fato.

Observar a expiração da credencial ocorre antes do RPC de revogação. Portanto pode existir expired sem revoked durante uma pausa do Vault. Uma revogação confirmada antes do prazo não gera uma expiração artificial posterior. O job do pedido funciona também com PAM habilitado e emissão de credenciais desabilitada. A lógica de validade continua dependendo de expiresAt, não do sucesso do observador.

Recusas HTTP são fatos de tentativas individuais, não transições deduplicadas por requestId/correlationId. Repetir uma chamada recusada pode gerar outro evento. A correlação não é chave de idempotência. Evitar inventar ator a partir de JWT não validado: token ausente/inválido ou identidade não confirmada não produz esse fato PAM. Erros de formato/validação de corpo, indisponibilidade de dependências e capacidade ocupada não são apresentados como recusas de política auditadas por este contrato.

Se a gravação da recusa autenticada falhar, o acesso continua negado e o endpoint sinaliza indisponibilidade; não simular persistência bem-sucedida. Emissão falha após adquirir o lease continua sem entregar segredo e segue compensação/limpeza da 9B2.

## Operação e limites

Aplicar V20 e reaplicar permissões com processos PAM parados; atualizar todos os workers antes de exigir o novo contrato. Workers antigos não produzem os novos fatos de expiração. Horário de servidor é parte da operação; esses observadores não constituem SLA de coleta nem prazo absoluto de sessão.

VALID UNTIL não encerra sessão já autenticada. Durante indisponibilidade do Vault, expired pode coexistir com sessão ativa; revoked exige confirmação do backend. Perda do estado do Vault dev, órfãos sem lease conhecido, retenção, fairness/backoff da fila e métricas detalhadas permanecem com 9C2/9C3/hardening. A etapa 9 também depende de KV do enforcer e aceite final.

Referências: [guia](../runbooks/auditoria-pam-local.md), [evidências](../evidencias/etapa-09c1.md), [ADR-039](039-emissao-pam-no-vault.md).
