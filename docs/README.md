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

Estado desta revisão: **10B5b — coleta conjunta local dos quatro exporters com IAM/enforcer, logs e traces**, em feat/tracing-backend sobre a8ccaed, versionada em commit local; push/CI remota pendentes. Doze bloqueios em 1 min mantiveram os seis scrapes saudáveis, com PostgreSQL/Redis conectados, Vault desbloqueado, série RabbitMQ, dashboards acessíveis, ACKs, traces e logs restritos. Seleção: **1 teste aprovado**; `verify` completo: **888 testes aprovados**, dois gates de cobertura e arquitetura atendidos. [Procedimento e limites](evidencias/etapa-10b5b.md). O aceite operacional global 10B permanece parcial pela ausência de agregador central de logs e prova de retenção/estabilidade prolongada. A 10C medirá capacidade. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.
