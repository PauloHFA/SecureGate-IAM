# ADR-050 — Exportação Prometheus protegida no IAM

- Data: 21/10/2026
- Estado: aceita para implementação local 10B1

## Contexto

Já existem meters HTTP/JVM e de dependências/Outbox/auditoria/PAM. O IAM expõe metrics JSON para bootstrap, mas não exportava Prometheus; enforcer não tem servidor HTTP. Implementar coleta, dashboards, logging e tracing em uma entrega dificultaria validar acesso e fronteiras.

## Decisão

Dividir 10B em 10B1 (inventário/exportação IAM), 10B2 (coleta/dashboard/enforcer/dependências) e 10B3 (logs/tracing). Usar micrometer-registry-prometheus gerenciado pelo BOM atual, endpoint opt-in e token Bearer dedicado METRICS_READ por arquivo privado. Cadeia exata sem sessão, somente GET, comparação de digest constante e erros sanitizados. Não reutilizar token administrativo ou aceitar credencial de métricas nas APIs de negócio.

Criar inicializador CSPRNG sem sobrescrita e flags explícitas no runner local. Histogramas HTTP opt-in, faixa esperada 1 ms–10 s; tag application fixa, sem identificadores de usuário/correlação. Preservar endpoint JSON administrativo e probes. Inventariar meters existentes e limites de disponibilidade/cardinalidade. Não ativar servidor web do enforcer, tocar schema/ACL ou subir serviços persistentes nessa fase.

## Consequências

Permite scrape autenticado e preparação de percentis agregáveis sem conceder administração ao scraper. Token por arquivo exige proteção local e reinício na rotação; padrão local HTTP loopback não é projeto de acesso remoto. Registry no classpath mantém instrumentação interna mesmo com rota desligada. Histogramas aumentam séries e memória; opt-in não prova capacidade. Coleta sustentada, exporters de dependências, dashboards, tracing/logs e hardening permanecem pendentes.

[Inventário](../OBSERVABILIDADE.md), [operação](../runbooks/metricas-prometheus-local.md), [evidências](../evidencias/etapa-10b1.md).
