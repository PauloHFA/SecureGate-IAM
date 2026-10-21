# ADR-051 — Coleta e dashboard local do IAM

- Data: 21/10/2026
- Estado: aceita para implementação local 10B2a

## Contexto

A 10B1 disponibiliza métricas IAM autenticadas, mas não havia collector/datasource/dashboard. O enforcer não tem HTTP; tratar sua exposição e exporters de dependências ao mesmo tempo ampliaria mudanças de runtime/permissões.

## Decisão

Dividir 10B2 em 10B2a (coleta/dashboard IAM) e 10B2b (enforcer/dependências). Compose independente, Prometheus 3.15.0 e Grafana 13.2.4 fixados por versão/digest, portas loopback e volumes próprios. Versões/releases conferidas em 21/10/2026 e imagens efetivamente baixadas/testadas. Configuração gerada privada contém somente alvo/portas; credenciais ficam em arquivos individuais, sem bootstrap/admin IAM no scraper. Grafana sem acesso anônimo e dashboard/datasource provisionados por arquivo.

Doze painéis, janela móvel e sem zero-fill para ausência de séries opcionais. Readiness dos containers é separado de up do alvo. Teste opt-in usa os mesmos arquivos/scripts copiados, IAM real e Docker Desktop, valida consultas/credencial incorreta/recuperação, sem tocar stack persistente. Padrão Maven não exige estas imagens. Não alterar aplicação Java, schema V22 ou grants SQL/ACL Vault.

## Consequências

Permite observar séries e abrir dashboard reproduzível no Windows/Docker Desktop. Retenção/memória são limites configurados, não prova de estabilidade/capacidade. API de consulta Prometheus fica sem autenticação apenas em loopback; acesso remoto/TLS exige revisão própria. Senha inicial Grafana fica persistida no banco; arquivo não é mecanismo automático de rotação. Segredos e volumes exigem proteção local. Linux, enforcer, exporters de dependências, alertas, tracing/logs estruturados e medição sustentada permanecem pendentes.

[Operação](../runbooks/coleta-iam-local.md), [inventário](../OBSERVABILIDADE.md), [evidências](../evidencias/etapa-10b2a.md).

Fontes primárias: [release Prometheus](https://github.com/prometheus/prometheus/releases/tag/v3.15.0), [release Grafana](https://github.com/grafana/grafana/releases/tag/v13.2.4), [configuração Prometheus](https://prometheus.io/docs/prometheus/latest/configuration/configuration/), [provisionamento Grafana](https://grafana.com/docs/grafana/latest/administration/provisioning/) e [configuração Grafana](https://grafana.com/docs/grafana/latest/setup-grafana/configure-grafana/). São referências de contrato/versão; somente resultados exercitados são declarados aprovados.
