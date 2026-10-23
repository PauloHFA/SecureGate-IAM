# Etapa 10C3 — consolidação e runbook unificado de carga

Data: 22/10/2026 (UTC−03). Branch `feat/load-comparative-baseline`.

## Método

A entrega **10C3** consolida as cinco linhas de base de carga desenvolvidas nas subdivisões 10C1–10C2 em uma automação unificada e padronizada:
1. **Runner comparativo ([`Run-ComparativeBaseline.ps1`](../../load-tests/jmeter/Run-ComparativeBaseline.ps1)):**
   - Prepara massas sintéticas isoladas para clientes confidenciais (`New-ClientMass.ps1`) e usuários humanos com políticas (`New-HumanMass.ps1`).
   - Executa sequencialmente os cinco cenários sob parâmetros homogêneos de concorrência (`Threads`), janelas móveis (`WindowSeconds`) e durações (`WarmupSeconds`, `SteadySeconds`, `CooldownSeconds`).
   - Suporta amostragem contínua de recursos do processo IAM (`-IamProcessId`) e dos containers Docker das dependências (`-DockerContainerIds`).
   - Gera um relatório estruturado consolidado (`comparative-summary.json`), uma planilha unificada (`comparative-summary.csv`) e preserva os subdiretórios individuais de cada fluxo com seus dashboards HTML e arquivos `samples.csv`.
2. **Jornadas operacionais comparadas:**
   - `issuance`: emissão via Client Credentials com assinatura Vault Transit (`token.success`).
   - `introspection`: introspecção HTTP autenticada de token ativo (`introspection.active`).
   - `resource`: validação em recurso protegido com token obtido via PKCE (`resource.validation.success`).
   - `revocation`: revogação autenticada (`token.revoke.call`) e observação de propagação de recusa via polling no recurso (`revocation.observed_refusal`).
   - `audit`: pipeline completo de auditoria com produção transacional outbox (`audit.produce.call`), visibilidade assíncrona após consumo RabbitMQ e gravação particionada (`audit.visibility.observed`) e consulta paginada por cursor (`audit.cursor.query`).
3. **Teste automatizado de integração ([`ComparativeBaselineSmoke.java`](../../integration-tests/src/test/java/com/securegate/e2e/ComparativeBaselineSmoke.java)):**
   - Execução ponta a ponta dos cinco fluxos contra PostgreSQL, Redis, RabbitMQ e Vault reais em Testcontainers.
   - Verificação estrita de ausência de falhas em todos os fluxos e validação de conformidade de metas.
   - Auditoria de segurança de artefatos garantindo zero vazamento de credenciais administrativas, senhas sintéticas ou JWTs.

## Validação local

Comando executado:
```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=ComparativeBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify
```

Resultado: **1 teste executado, 0 falhas, 0 erros, 0 ignorados; BUILD SUCCESS** em 3 min 02 s (tempo total 3 min 08 s).

### Matriz comparativa no regime estacionário (`steady`, 5 s, 2 threads):

| Cenário | Descrição | Requisições | Taxa (req/s) | p50 (ms) | p95 (ms) | Métrica secundária | Alvo | Atendido | Working set IAM |
| --- | --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `issuance` | Client Credentials | 106 | 21.2 | 73 | 79 | — | p95 < 200 ms | Sim | 376.8 MiB |
| `introspection` | Introspecção HTTP | 98 | 19.6 | 80 | 94 | — | — | — | 391.5 MiB |
| `resource` | Validação no recurso | 246 | 49.2 | 21 | 34 | — | — | — | 440.7 MiB |
| `revocation` | Revogação e propagação | 20 | 4.0 | 77 | 110 | Propagação: 90 ms | < 2000 ms | Sim | 431.5 MiB |
| `audit` | Pipeline de auditoria | 16 | 3.2 | 51 | 1564 | Visibilidade: 1119 ms / Cursor: 14 ms | < 5000 ms | Sim | 507.8 MiB |

- Falhas em todos os 5 cenários: **0**.
- Erros de amostragem de containers Docker (Postgres, Redis, RabbitMQ, Vault): **0**.
- Verificação de vazamento de segredos: **0 credenciais encontradas nos artefatos**.

## Limites

Os valores refletem o ambiente de teste local em máquina de desenvolvimento compartilhada (Docker Desktop / loopback). A consolidação unificada não altera os requisitos nem substitui ensaios prolongados de capacidade da Etapa 10D. [Runbook operacional](../runbooks/medicao-linha-base-unificada-local.md).
