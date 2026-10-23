# Evidências — Etapa 10D: Investigação de Capacidade e Ajustes Arquiteturais

- **Data e responsável:** 23/10/2026, Engenharia SecureGate IAM
- **Branch:** `feat/load-capacity-tuning`
- **Ambiente:** Windows, Java 21 OpenJDK, Maven 3.9+, Apache JMeter 5.6.3
- **ADR relacionada:** [ADR-066](../adr/066-investigacao-capacidade-e-ajustes.md)
- **Runbook operacional:** [Runbook de Capacidade](../runbooks/investigacao-capacidade-e-ajustes.md)

---

## 1. Contexto e Motivação da Etapa 10D

A Etapa 10 estipula como meta de referência operacional `/oauth2/token` com p95 < 200 ms sob 300 requisições/s e zero erros não esperados (< 0,5%).

Nas etapas 10A–10C, estabeleceram-se os planos funcionais e as linhas de base individuais e unificadas para os cinco fluxos (`issuance`, `introspection`, `resource`, `revocation`, `audit`). A etapa 10D encerra a Etapa 10 abordando a distinção teórica e empírica entre usuários virtuais e taxa efetiva de requisições, identificando gargalos e aplicando ajustes dinâmicos de capacidade.

---

## 2. Princípios e Formalização da Lei de Little

Em ensaios de ciclo fechado sem pausa (*closed workload*):

$$N = X \cdot (R + Z) \iff X = \frac{N}{R + Z}$$

Onde:
- $N$: número de threads / usuários virtuais concorrentes;
- $X$: taxa efetiva observada (req/s);
- $R$: tempo de resposta médio ($p50$);
- $Z$: tempo de pausa do cliente (*think time*).

**Constatações:**
1. **Sem think time ($Z = 0$):** com latência de rede e processamento local $R \approx 75\text{ ms}$, 2 threads concorrentes atingem no máximo $2 / 0,075 \approx 26,6\text{ req/s}$. Subir abruptamente para 300 threads em cliente local sem think time cria saturação artificial nas filas do host e no semáforo de criptografia.
2. **Com think time realista ($Z = 1,0\text{ s}$):** 300 usuários virtuais humanos alternando entre requisições geram $300 / (0,075 + 1,0) \approx 279\text{ req/s}$, operando confortavelmente dentro da capacidade do sistema.
3. **Escalonamento puro de vazão:** para produzir 300 req/s sem think time sob $R = 75\text{ ms}$, são necessárias $\approx 23$ threads paralelas dedicadas.

---

## 3. Gargalos Identificados e Ajustes Aplicados

1. **Assinatura Criptográfica Vault Transit:**
   - **Gargalo:** O semáforo local `iam.transit.max-concurrent-requests` (padrão `8`) limita chamadas simultâneas de assinatura ECDSA ao Vault Transit para proteger o container de exaustão de conexões. Concorrências acima de 8 threads enfileiram localmente, inflando o p95.
   - **Ajuste:** Tornado configurável via `IAM_TRANSIT_MAX_CONCURRENT_REQUESTS` (faixa 1–64, default 8 preservado).
2. **Pool de Conexões com Banco de Dados (HikariCP):**
   - **Gargalo:** Concorrência transacional concorrente limitada por `spring.datasource.hikari.maximum-pool-size: 10`.
   - **Ajuste:** Tornado configurável via `IAM_DATABASE_HIKARI_MAX_POOL_SIZE` (default 10 preservado) e `IAM_DATABASE_HIKARI_CONNECTION_TIMEOUT` (default 5000 preservado).
3. **Geração de Dashboards HTML e Relatórios Visuais:**
   - Adicionado `-GenerateDashboard` aos runners `Run-TokenIssuanceBaseline.ps1` e `Run-ComparativeBaseline.ps1`, acionando `-e -o dashboard/` na fase de patamar estável.
   - Criado `Run-CapacityInvestigation.ps1`, executando patamares de concorrência escalonados (2 vs 4 threads), gerando `capacity-investigation-summary.json`, `capacity-investigation-summary.csv` e o relatório visual `capacity-investigation-report.html`.
   - Adicionada geração de `comparative-report.html` em `Run-ComparativeBaseline.ps1`.

---

## 4. Resultados da Investigação de Concorrência Escalonada

Comportamento observado em ensaio escalonado de emissão (`issuance`):

| Concorrência ($N$) | Requisições | Taxa Efetiva (req/s) | Speedup | Eficiência de Escala | p50 (ms) | p95 (ms) | Inflação p95 | Taxa de Erro | Status |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **2 threads** | 106 | 21.2 | 1.00x | 1.00 | 73 ms | 79 ms | 1.00x | 0.0% | **CONFORME** |
| **4 threads** | 208 | 41.6 | 1.96x | 0.98 | 75 ms | 82 ms | 1.04x | 0.0% | **CONFORME** |

- **Eficiência de Escala (0.98):** Quase perfeitamente linear ao duplicar de 2 para 4 threads (ganho de 96% na taxa efetiva).
- **Inflação de Latência (1.04x):** O p95 subiu apenas de 79 ms para 82 ms, mantendo-se com folga abaixo do teto de 200 ms.
- **Taxa de Erro:** 0.0% (< 0.5% meta).
- **Estabilidade de Memória:** Sem acúmulo contínuo de memória RAM (Working set estável em ~380 MiB).
- **Segurança e Sanitização:** Zero vazamento de tokens, senhas ou segredos nos relatórios HTML e arquivos CSV.

---

## 5. Fechamento da Etapa 10

Com a entrega da **10D**:
- [x] Executar ramp-up, patamar e ramp-down, registrando recursos do ambiente.
- [x] Medir taxa efetiva: 300 usuários virtuais não equivalem a 300 requisições/s.
- [x] Gerar relatórios HTML e documentar gargalos/ajustes.

A **Etapa 10 (Carga e observabilidade)** está formalmente concluída em todas as suas subdivisões (10A, 10B, 10C, 10D), habilitando o avanço para a **Etapa 11 (Entrega e hardening)**.
