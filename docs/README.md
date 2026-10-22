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

Estado desta revisão: **10C2b2b1 — validação no recurso protegido**, em feat/tracing-backend sobre 059cc48, sem commit/push/CI remota. O plano JMeter prepara um token humano por thread e fase, mede somente o GET protegido e registra separadamente as preparações PKCE. A seleção curta descartável passou: 510 GETs no patamar, zero falhas, 51 req/s e p95 47 ms. [Procedimento e limites](evidencias/etapa-10c2b2b1.md). A seleção de 60 s está em execução. O p95 do GET completo não isola a validação criptográfica/cache, e a taxa inclui o tempo inicial de preparação no denominador. O `verify` completo mais recente da 10B5b aprovou 888 testes; ele ainda não foi repetido após 10C1–10C2b2b1. Revogação e auditoria seguem nas próximas divisões. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.
