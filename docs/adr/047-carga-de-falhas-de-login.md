# ADR-047 — Contratos de falha de login no JMeter

- Data: 21/10/2026.
- Estado: implementada localmente na 10A2b1; resultados nas evidências.

## Contexto e divisão

Após Client Credentials e PKCE, a 10A2b reúne negativas humanas, recursos/revogação e auditoria. Separar 10A2b1 (senha incorreta/usuário inexistente), 10A2b2 (recursos/revogação) e 10A2b3 (auditoria) permite distinguir contrato de recusa, bloqueio por repetição e falhas operacionais.

## Decisão

Criar usuários sintéticos pela API, com senha CSPRNG e candidato ausente aleatório, sem criar clientes OAuth/políticas. Token administrativo fica na preparação, por arquivo; dados de entrada privados permanecem em diretórios ignorados. A fixture confirma a ausência do candidato fora da janela cronometrada.

Usar plano JSR223/Groovy com cookies próprios por tentativa e CSRF real. Exigir redirect para erro, comparar a mensagem genérica dos dois casos, recusar identificadores/senha no HTML e verificar que /mfa continua exigindo login. Não contar erro de CSRF, rate limit ou indisponibilidade como rejeição esperada de credenciais.

O padrão é uma thread/uma iteração para reduzir interferência dos limiares de bloqueio. O teste opt-in usa JARs/serviços reais descartáveis, exige dois fatos de falha na Outbox e prova que -UseCorrectPassword detecta autenticação inesperada. Dados/contexto de uma tentativa recusada não são reutilizados após falha do contrato. Reutilizar o perfil seguro de resultados da 10A1 e manter o build padrão independente de JMeter.

## Consequências e limites

Não há mudança de aplicação, schema, ACL ou limites de proteção. A fixture configura perfil de risco sintético só em ambiente descartável para o controle com senha válida; o gerador não altera risco/MFA/Redis. Não é comparação de tempo constante ou garantia geral contra enumeração de contas. Mensagens/estado de sessão são verificados sem guardar corpos ou dados privados nos resultados.

Cada amostra agrega quatro chamadas e parsing; métricas de rede do JSR223 não são instrumentadas. Repetições podem ativar bloqueios reais e devem falhar, não ser mascaradas como negativas esperadas. O controle com senha correta cria sessão e falha intencionalmente: é apenas prova de detecção, não carga de sucesso. Fatos na Outbox são conferidos fora da janela; ingestão/consulta central sob carga continua na 10A2b3.

Ver [operação](../../load-tests/jmeter/LOGIN-FAILURES.md) e [evidências](../evidencias/etapa-10a2b1.md).
