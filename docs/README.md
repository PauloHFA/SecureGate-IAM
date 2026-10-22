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

Estado desta revisão: **10C2b1 — introspecção autenticada de token ativo**, em feat/tracing-backend sobre 0ea1957, versionada em commit local; push e CI remota pendentes. O novo plano JMeter isola `introspection.active`, com token sintético renovado por fase e CSV temporário removido após cada execução. A seleção descartável de 60 s com duas threads passou: 1.330 introspecções, zero falhas, 22,17 req/s e p95 103 ms no agregado; cinco janelas de 10 s variaram entre 21 e 23 req/s. [Procedimento e limites](evidencias/etapa-10c2b1.md). Introspecção HTTP não é a validação local com cache do recurso, logo a referência p95 < 20 ms não foi aplicada. O `verify` completo mais recente da 10B5b aprovou 888 testes; ele ainda não foi repetido após 10C1–10C2b1. A 10C2b2 medirá os demais fluxos e recursos das dependências. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.
