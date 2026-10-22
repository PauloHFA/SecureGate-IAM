# ADR-048 — Smoke JMeter de recurso protegido e revogação

- Data: 21/10/2026
- Estado: aceita para implementação local da 10A2b2

## Contexto

A 10A2a entrega PKCE e a 10A2b1 falhas de login. Faltava exercitar acesso Bearer e revogação no conjunto de planos JMeter. O recurso atual também verifica grant/ledger local; recusa HTTP não é prova isolada da propagação distribuída.

## Decisão

Reutilizar gerador e jornada PKCE com extensão opt-in, mantendo o plano anterior. IncludeAccountPolicy cria ALLOW_LIMITED account:self/read (api:read, TTL máximo 120 s) na stack dedicada. Reter token/expiração apenas em memória para o novo plano; usar HttpClient sem cookies no recurso/revogação. Separar cinco rótulos: PKCE, acesso, token inválido, revogação e recusa posterior. Polling limitado, com verificação de validade residual, sem aceitar expiração/bloqueios como sucesso. Redigir todos os resultados e exigir contagens/contratos no runner.

Teste opt-in usa JARs/serviços reais, confere publicação/projeção fora do tempo JMeter e detecta omissão de revogação com controle positivo de acesso. Executar também JMeterHumanSmoke como regressão das extensões compartilhadas. Não modificar aplicação, migrations (V22 atual), ACL, limiares ou stack persistente.

## Consequências

Permite repetir a jornada com massa privada e verificar que o runner acusa token ainda válido após uma revogação ausente. A política de conta não restringe um único sujeito: somente ambiente dedicado. Polling e elapsed não fornecem latência isolada de rede nem SLA de propagação. Persistem limites de proteção dos artefatos, criação parcial e interrupção forçada. Auditoria/observabilidade/capacidade continuam pendentes.

Operação e limitações: [plano executável](../../load-tests/jmeter/RESOURCE-REVOCATION.md). Validação: [evidências 10A2b2](../evidencias/etapa-10a2b2.md).
