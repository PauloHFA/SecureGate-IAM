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

Estado desta revisão: **10C2a — janelas temporais da emissão de token**, em feat/tracing-backend sobre 09865d5, versionada em commit local; push e CI remota pendentes. O runner JMeter agora divide o patamar em janelas completas e registra taxa, falhas e p95 por janela. Uma seleção descartável de 60 s com duas threads passou: 1.312 emissões, zero falhas, 21,87 req/s e p95 121 ms no agregado; cinco janelas de 10 s variaram entre 18,6 e 24,4 req/s. Isso ainda não demonstra a referência de 300 req/s. [Procedimento e limites](evidencias/etapa-10c2a.md). O `verify` completo mais recente da 10B5b aprovou 888 testes; ele ainda não foi repetido após a 10C1/10C2a. A 10C2b medirá os demais fluxos e recursos das dependências. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.
