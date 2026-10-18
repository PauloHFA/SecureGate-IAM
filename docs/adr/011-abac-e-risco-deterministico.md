# ADR-011 — ABAC e risco determinístico

- Data: 18/10/2026
- Estado: aceito para a entrega 5B1

## Contexto e divisão

A 5A estabelece persistência e combinação de efeitos. Não existe ainda coleta confiável de dispositivo, rede, falhas de login ou sensibilidade de recursos. A requisição de um administrador que simula outro usuário não representa a rede/dispositivo desse usuário. Headers, IP e User-Agent desse pedido não podem ser promovidos a evidência.

Dividir a 5B: **5B1**, motor ABAC/risco e cenários administrativos; **5B2**, coleta real com origem, vínculo ao sujeito/recurso e validade. Esta decisão não declara os coletores implementados.

## Gramática v2

Preservar a estrutura v1 e seu comportamento, incluindo serialização sem campo novo. A v2 acrescenta `conditions`, uma lista obrigatória, possivelmente vazia, de no máximo dez condições combinadas por ALL. Cada condição possui somente `attribute`, `operator` e `values`, com no máximo dez literais textuais de até 32 caracteres. Não há árvore recursiva, NOT, scripts, reflexão, SpEL, regex configurável ou caminho de atributo escolhido pelo solicitante.

| Atributo | Valores | Operadores |
| --- | --- | --- |
| DEVICE_TRUST / NETWORK_TRUST | TRUSTED, UNTRUSTED | EQ, IN |
| RESOURCE_SENSITIVITY | PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED | EQ, IN |
| RISK_LEVEL | LOW, MEDIUM, HIGH, CRITICAL | EQ, IN |
| FAILED_LOGIN_ATTEMPTS | Inteiro decimal de 0–1000 | EQ, GTE, LTE |
| RISK_SCORE | Inteiro decimal de 0–100 | EQ, GTE, LTE |

EQ/GTE/LTE exigem um literal; IN aceita uma lista. Números das condições são strings canônicas, por exemplo `"29"`; números JSON e strings com sinal/zeros à esquerda são rejeitados. SchemaVersion continua um número JSON. Campos desconhecidos no documento/condição e versões sem suporte são rejeitados.

A migração V6 permite documentos v1/v2 sem reescrever os existentes nem alterar versões de políticas. Adicionar `conditions` a um documento v1 é erro. Evolução de uma política exige PUT explícito com versão otimista.

## Ausência de atributos e combinação

Cada condição retorna MATCH, NO_MATCH ou INDETERMINATE. Ausência de fato concreto não é um valor comparável como TRUSTED ou UNTRUSTED. Para ALL, uma condição comprovadamente falsa resulta em NO_MATCH, mesmo se outra estiver indeterminada. Target ou papel incompatível também resulta em NO_MATCH antes das condições.

Considerar todas as regras habilitadas que correspondam ou permaneçam potencialmente aplicáveis. DENY comprovado prevalece com EXPLICIT_DENY. Se qualquer regra potencialmente aplicável continuar indeterminada, retornar DENY/INDETERMINATE_ATTRIBUTES, sem conceder acesso por outra regra. Somente sem indeterminação aplicar a combinação da [ADR-010](010-politicas-e-tabela-de-decisao.md). IDs/versões das regras potencialmente participantes continuam disponíveis na explicação.

O score e sua faixa permanecem determinados mesmo com fatos ausentes, usando pesos conservadores; condições de RISK_SCORE/RISK_LEVEL podem assim negar ou exigir MFA por contexto desconhecido. Uma política v1 sem condição de risco não muda seu efeito apenas porque o score é alto. O cálculo é informação adicional; decisões automáticas dependem das políticas explícitas.

## Cálculo inicial

Soma fixa, determinística, de 0–100, sem aleatoriedade ou consulta externa:

| Sinal | Pontos |
| --- | --- |
| Dispositivo TRUSTED | 0 |
| Dispositivo UNTRUSTED ou ausente | 25 |
| Rede TRUSTED | 0 |
| Rede UNTRUSTED ou ausente | 20 |
| Falhas de login recentes: 0 / 1–2 / 3–4 / ≥5 / ausente | 0 / 10 / 20 / 30 / 30 |
| Recurso PUBLIC / INTERNAL / CONFIDENTIAL / RESTRICTED / ausente | 0 / 5 / 15 / 25 / 25 |

Faixas: LOW 0–29, MEDIUM 30–59, HIGH 60–79, CRITICAL 80–100. Retornar score, faixa, motivos e nomes dos sinais ausentes, sem identificadores de rede/dispositivo. Esses pesos são uma baseline de engenharia, sem calibração estatística ou meta de detecção comprovada.

O contrato de sinais traz instante de observação. Observação ausente, anterior a 300 segundos ou mais de cinco segundos no futuro invalida o conjunto inteiro, produzindo score 100 e sinais UNKNOWN. Os limites exatos de 300 segundos de idade e cinco segundos no futuro são aceitos. O relógio é UTC e injetável para testes. Na coleta futura, falhas recentes devem representar a janela de cinco minutos, sem aceitar contador autodeclarado.

Valores impossíveis, como contador negativo/acima de 1000, são rejeitados. Erros de coleta não podem ser convertidos silenciosamente em contexto seguro. A implementação normal atual usa explicitamente `UnavailableRiskSignals`, que retorna ausência dos quatro sinais; não é fallback para uma coleta que falhou.

## Administração e confiança

`POST /api/admin/policies/evaluate` usa somente a porta de coleta do servidor. Atualmente informa score 100 e os quatro sinais ausentes, pois os coletores ainda não existem; identidade ACTIVE e papéis continuam consultados no banco. Dados adicionais de contexto/score fornecidos no pedido não alteram a coleta.

`POST /api/admin/policies/simulate` é separado, sob o mesmo bootstrap administrativo, e recebe um cenário sintético limitado. Toda resposta desse endpoint traz `simulation: true`; a avaliação normal traz `false`. O cenário nunca é salvo como evidência, não muda a próxima avaliação normal, não sobrescreve papéis/estado da identidade e não comprova MFA. O instante do cenário é definido pelo servidor. Não existe entrada direta para forçar um score: ele sempre é calculado.

Ambos continuam sendo ferramentas administrativas de recomendação, sem aplicação em login, tokens ou recursos. Não registrar eventos de concessão de acesso com esses resultados. A 5B2 precisará definir produtores autorizados, vínculo a usuário/dispositivo/recurso, atualização, expiração e resistência a replay antes de fornecer fatos concretos à porta.

## Validação e limites

Testes de relógio fixo cobrem pesos, faixas, expiração, futuro, condições tipadas, indeterminação e retrocompatibilidade. Integração verifica distinção entre simulação e coleta, CRUD v2, migração real V5→V6 preservando v1 e os limites herdados de MFA/TTL/escopos. Mantêm-se os limites JaCoCo e ArchUnit.

Coleta real permanece na 5B2; TOTP/recuperação/step-up na 5C; aplicação das decisões na 5D. Nenhuma mudança de segredos, login ou emissão OAuth nesta entrega.

Referências: [guia ABAC/risco](../runbooks/abac-risco-local.md), [evidências](../evidencias/etapa-05b1.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
