# ADR-066 — Investigação de capacidade, análise da Lei de Little e ajustes arquiteturais

- Data: 23/10/2026.
- Estado: implementada localmente na 10D; resultados nas evidências.

## Contexto e limites observados

As etapas 10A–10C entregaram planos funcionais de carga (Client Credentials, PKCE, falhas de login, validação de recursos, revogação e auditoria centralizada), instrumentação de observabilidade (Prometheus, logs JSON, tracing OTLP/Tempo) e automação comparativa unificada.

A meta de referência do projeto estipula `/oauth2/token` com p95 < 200 ms a 300 requisições/s. Entretanto, observou-se empiricamente que executar 300 threads (usuários virtuais) em cliente fechado sem pausas não resulta em 300 req/s, mas sim em saturação e enfileiramento artificial. Era necessário:
1. Formalizar a relação entre usuários virtuais, taxa efetiva (throughput) e tempo de resposta via **Lei de Little** ($N = X \cdot (R + Z)$).
2. Identificar e documentar os gargalos arquiteturais do sistema: o semáforo de assinatura criptográfica no Vault Transit (`iam.transit.max-concurrent-requests`), o pool de conexões com banco de dados (`HikariCP`), e a retenção assíncrona.
3. Tornar os limites de capacidade ajustáveis via variáveis de ambiente mantendo os valores padrão estritamente preservados.
4. Gerar relatórios HTML consolidados e dashboards nativos JMeter para documentação visual e auditoria de desempenho.

## Decisão

1. **Formalização da Lei de Little:**
   Pela Lei de Little em ciclos fechados:
   $$N = X \cdot (R + Z) \iff X = \frac{N}{R + Z}$$
   Onde $N$ é a concorrência (threads/usuários virtuais), $X$ é a vazão efetiva (req/s), $R$ é a latência média e $Z$ é o tempo de pensar do cliente (think time).
   - Com $R \approx 75\text{ ms}$ e $Z = 0$, 2 threads geram no máximo $2 / 0,075 \approx 26,6\text{ req/s}$.
   - Para alcançar 300 req/s contínuos com $R = 75\text{ ms}$ sem pausa, são necessárias $300 \cdot 0,075 = 22,5$ threads ativas simultâneas.
   - Quando 300 usuários virtuais representam clientes reais com think time de $Z \approx 1\text{ s}$, a vazão resultante é de $300 / (0,075 + 1) \approx 279\text{ req/s}$, sem estresse excessivo no loopback local.

2. **Ajustes de Capacidade Configuráveis em Tempo de Execução:**
   - Tornar o tamanho do pool HikariCP configurável via `IAM_DATABASE_HIKARI_MAX_POOL_SIZE` (padrão mantido em `10`) e `IAM_DATABASE_HIKARI_CONNECTION_TIMEOUT` (padrão `5000`).
   - Tornar o semáforo de assinatura criptográfica no Vault Transit configurável via `IAM_TRANSIT_MAX_CONCURRENT_REQUESTS` (padrão mantido em `8`, faixa válida 1–64).
   - Preservar 100% da compatibilidade regressiva: sem variáveis no ambiente, os valores de produção permanecem idênticos aos das etapas anteriores.

3. **Geração de Relatórios HTML e Dashboards JMeter:**
   - Adicionar o parâmetro `-GenerateDashboard` ao runner `Run-TokenIssuanceBaseline.ps1`, acionando `-e -o dashboard/` na fase de patamar (`steady`).
   - Adicionar geração de `comparative-report.html` em `Run-ComparativeBaseline.ps1`.
   - Criar o script `Run-CapacityInvestigation.ps1`, executando ensaios de concorrência escalonada (ex.: 2 e 4 threads), calculando speedup, eficiência de escala e gerando `capacity-investigation-summary.json`, `capacity-investigation-summary.csv` e `capacity-investigation-report.html`.

4. **Verificação Automatizada e Integridade:**
   - Implementar o teste opt-in `CapacityInvestigationSmoke.java` com Testcontainers reais (PostgreSQL, Redis, RabbitMQ e Vault).
   - Validar taxa de erro 0,0% (< 0,5%), conformidade de p95, estabilidade de memória e auditoria estrita contra vazamento de credenciais/JWTs em todos os artefatos gerados.

## Consequências

- O projeto passa a contar com documentação matemática e empírica da capacidade do núcleo IAM, explicando com precisão a distinção entre usuários virtuais e taxa de requisições por segundo.
- Ambientes de homologação ou clusters multi-core podem ajustar pools e semáforos via ambiente sem recompilar código ou alterar arquivos YAML.
- Conclui todos os itens de aceite e requisitos pendentes da **Etapa 10**, habilitando a transição para a **Etapa 11 (Entrega e hardening)**.
- Sem novas tabelas, migrations ou quebra de contratos existentes.
