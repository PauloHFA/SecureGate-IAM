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

Estado desta revisão: **10B5a — coleta conjunta de métricas, logs JSON locais e traces IAM/enforcer validada localmente**, em feat/tracing-backend sobre cc95318, versionada em commit local, sem push/CI remota. Doze bloqueios em 1 min mantiveram os dois scrapes disponíveis, produziram 12 ACKs e traces consultáveis no começo, meio e fim do patamar; logs de ambos os processos seguiram o esquema restrito. `verify` completo: **888 testes aprovados**, dois gates de cobertura e arquitetura atendidos. [Procedimento e limites](evidencias/etapa-10b5a.md). A ressalva histórica do fork IAM permanece. A 10B5b ainda deve reunir os quatro exporters de dependências para o aceite global 10B antes da medição 10C. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.
