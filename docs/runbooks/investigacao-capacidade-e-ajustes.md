# Runbook — Investigação de Capacidade, Lei de Little e Ajustes (Etapa 10D)

Este runbook orienta a execução e interpretação dos ensaios de capacidade, concorrência escalonada e ajustes arquiteturais do SecureGate IAM.

## 1. Princípios Teóricos e Limites de Capacidade

### Lei de Little em Ciclo Fechado

A taxa de requisições por segundo ($X$) observada em testes de carga é dada por:

$$N = X \cdot (R + Z) \iff X = \frac{N}{R + Z}$$

Onde:
- $N$: número de threads concorrentes (usuários virtuais);
- $X$: taxa efetiva de atendimento (throughput em req/s);
- $R$: tempo de resposta médio da jornada ($p50$ / média);
- $Z$: tempo de espera ou pausa entre requisições (*think time*).

**Por que 300 usuários virtuais não equivalem a 300 requisições/s:**
- Sem *think time* ($Z = 0$) e com latência média $R \approx 75\text{ ms}$, 2 threads concorrentes produzem no máximo:
  $$X = \frac{2}{0,075} \approx 26,6\text{ req/s}$$
- Para atingir 300 req/s em regime contínuo sob $R = 75\text{ ms}$ sem pausa, seriam necessárias pelo menos:
  $$N = 300 \cdot 0,075 = 22,5\text{ threads concorrentes simultâneas}$$
- Se 300 usuários virtuais representarem usuários reais com *think time* de 1 segundo ($Z = 1,0\text{ s}$):
  $$X = \frac{300}{0,075 + 1,0} \approx 279\text{ req/s}$$
  O que atende à meta operacional sem saturar os recursos de CPU e rede do host de desenvolvimento.

---

## 2. Ajustes Arquiteturais e Parâmetros de Sintonização

Na Etapa 10D, os seguintes controles foram tornados dinamicamente configuráveis via variáveis de ambiente, mantendo rigorosa paridade regressiva com os padrões anteriores:

| Parâmetro | Variável de Ambiente | Padrão | Faixa Recomendada | Descrição |
| --- | --- | --- | --- | --- |
| Assinatura Transit | `IAM_TRANSIT_MAX_CONCURRENT_REQUESTS` | `8` | 1–64 | Semáforo limitador de requisições paralelas ao Vault Transit. |
| Timeout Transit | `IAM_TRANSIT_TIMEOUT` | `2s` | 1s–10s | Timeout HTTP de comunicação com o Vault. |
| Cache JWKS Transit | `IAM_TRANSIT_KEY_CACHE_TTL` | `30s` | 5s–300s | TTL do cache local da chave pública para validação de JWTs. |
| Pool HikariCP | `IAM_DATABASE_HIKARI_MAX_POOL_SIZE` | `10` | 5–50 | Número máximo de conexões JDBC simultâneas ao PostgreSQL. |
| Timeout HikariCP | `IAM_DATABASE_HIKARI_CONNECTION_TIMEOUT` | `5000` | 1000–30000 | Tempo máximo de espera (ms) por conexão disponível do pool. |

---

## 3. Execução da Investigação de Capacidade

O runner `Run-CapacityInvestigation.ps1` automatiza o ensaio comparativo em múltiplos patamares de concorrência com geração de relatórios tabulares, JSON estruturado e relatório HTML visual.

### Sintaxe Básica (PowerShell)

```powershell
pwsh load-tests/jmeter/Run-CapacityInvestigation.ps1 `
  -JMeterHome "/opt/apache-jmeter-5.6.3" `
  -BaseUri "http://127.0.0.1:8080" `
  -AdminTokenFile "load-tests/.local/admin.token" `
  -OutputDirectory "load-tests/.local/capacity-run-01" `
  -Scenario "issuance" `
  -ConcurrencyLevels 2, 4 `
  -WarmupSeconds 3 `
  -SteadySeconds 10 `
  -WindowSeconds 2 `
  -CooldownSeconds 3 `
  -GenerateDashboard
```

### Parâmetros Suportados

- `-Scenario`: Cenário sob teste (`issuance`, `introspection`, `resource`, `revocation`, `audit`).
- `-ConcurrencyLevels`: Array de inteiros indicando os patamares de concorrência a testar sequencialmente (ex.: `2, 4` ou `2, 4, 8`).
- `-GenerateDashboard`: Gera o Dashboard HTML nativo do JMeter (`dashboard/index.html`) para cada patamar.
- `-IamProcessId`: PID do processo JVM do IAM Core para coleta de CPU e memória.
- `-DockerContainerIds`: Lista separada por vírgula dos IDs de container Docker a monitorar via `docker stats`.

---

## 4. Estrutura de Artefatos Gerados

Após a execução, o diretório de saída contém:

```text
<OutputDirectory>/
├── capacity-investigation-summary.json   # Resumo estruturado com métricas e análise
├── capacity-investigation-summary.csv    # Tabela consolidada para planilhas
├── capacity-investigation-report.html    # Relatório executivo visual autocontido
├── concurrency-2/
│   ├── summary.json                     # Métricas detalhadas do patamar 2 threads
│   ├── warmup/                          # Amostras do aquecimento
│   ├── steady/                          # Amostras do patamar
│   │   ├── samples.csv                  # Registros sanitizados
│   │   └── dashboard/                   # Dashboard HTML nativo JMeter (se habilitado)
│   └── cooldown/                        # Amostras da desaceleração
└── concurrency-4/
    ├── summary.json
    └── steady/
        ├── samples.csv
        └── dashboard/
```

---

## 5. Interpretação dos Resultados e Métricas

1. **Taxa de Erro:** Deve permanecer rigorosamente em **0,0%** (< 0,5% do critério de aceite).
2. **Speedup:** Razão entre a taxa efetiva no patamar escalonado e o patamar base ($X_T / X_1$).
3. **Eficiência de Escala:** $Speedup / (T / T_1)$. Valores próximos a 1,0 indicam escala perfeitamente linear; valores entre 0,7 e 0,9 indicam concorrência saudável com overhead mínimo.
4. **Inflação de p95:** Razão $p95_T / p95_1$. Não deve ultrapassar as metas de referência ($p95 < 200\text{ ms}$ em emissão, $p95 < 20\text{ ms}$ em validação com cache).
5. **Estabilidade de Memória:** O consumo de Working Set / Heap JVM deve se manter bounded durante o patamar, sem crescimento monotônico sustentado.

---

## 6. Procedimento de Segurança

- Os diretórios de execução ficam restritos a `load-tests/.local/` ou `integration-tests/target/`.
- Todos os relatórios HTML, CSV e JSON utilizam identificadores sintéticos e mascaramento automático via `safe-results.properties`.
- Nenhum token administrativo, segredo de cliente ou chave JWT é registrado em disco ou logs.
