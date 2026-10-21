# Documentação — SecureGate IAM

Começar pelo [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md): ele reúne a sequência completa, todas as subdivisões executadas, as próximas divisões propostas e o mapa de migrações.

| Documento | Quando consultar |
| --- | --- |
| [Plano de implementação](PLANO-DE-IMPLEMENTACAO.md) | Escopo, dependências, tarefas e critérios de aceite das etapas 0–12. |
| [Guia passo a passo](GUIA-DE-IMPLEMENTACAO.md) | Ordem de construção, motivos das divisões, como validar e como registrar a próxima entrega. |
| [Observabilidade](OBSERVABILIDADE.md) | Inventário de métricas, acesso de leitura, histogramas e limites das entregas 10B. |
| [ADRs](adr/) | Decisões arquiteturais, alternativas e limites de cada entrega. |
| [Guias operacionais](runbooks/) | Preparação, execução, flags, testes e recuperação. |
| [Evidências](evidencias/) | Comandos executados, resultados observados, ambiente e pendências históricas. |

Estado desta revisão: **10B2b2c — coleta Redis implementada localmente**, em feat/redis-prometheus-metrics sobre 6a48bb6, validada por smoke e regressão IAM, incluída neste commit; push pendente. Próximas: 10B2b2d Vault e 10B3 logs/tracing. A 10B2b2b está versionada em 6a48bb6; publicação/CI e aceite global de observabilidade/capacidade continuam pendentes.

Consultar [inventário](OBSERVABILIDADE.md), [runbook Redis](runbooks/metricas-redis-local.md), [ADR-055](adr/055-coleta-opcional-redis.md) e [evidências 10B2b2c](evidencias/etapa-10b2b2c.md). ACL própria persistente sem acesso a chaves, exporter opcional e dashboard de seis painéis. O [aceite local da etapa 9](runbooks/aceite-etapa9.md) preserva seus riscos e limites.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10B2b2c e registra suas divisões passo a passo.
