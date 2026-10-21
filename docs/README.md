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

Estado desta revisão: **10B3b1 — tracing HTTP entrante implementado e validado localmente**, em feat/http-server-tracing sobre b6e2363, incluída neste commit; push pendente. Build completo: 804 testes aprovados, cobertura/arquitetura atendidas; ressalva de encerramento Hikari nas evidências. Perfis http-tracing e structured-logs, contexto W3C limitado e schema finito, sem exporter/backend. Próxima: 10B3b2 clientes JDK/Vault; depois 10B3c outbox/RabbitMQ. [Operação](runbooks/tracing-http-local.md), [ADR-058](adr/058-tracing-http-entrante-restrito.md), [evidências](evidencias/etapa-10b3b1.md). Publicação/CI e aceite global continuam pendentes.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também a 10B3b1 e registra suas divisões passo a passo.
