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

Estado desta revisão: **10B4b — exportação OTLP opcional IAM/enforcer implementada**, em feat/tracing-backend sobre 38dd1ae, junto à 10B4a no commit local desta branch, sem push/CI remota. Destino local validado, fila/timeouts limitados, transporte JDK sem redirects; 38 casos de exportação aprovados; build completo com **886 testes aprovados**, gates de cobertura/arquitetura atendidos e bloqueio NIO do enforcer corrigido com regressão de recuperação de entrega. Ressalva histórica do fork IAM permanece. [Operação](runbooks/exportacao-otlp-local.md), [ADR-065](adr/065-exportacao-otlp-local.md), [evidências](evidencias/etapa-10b4b.md). Backend 10B4a validado com 847 testes, gates e smoke dos scripts, ressalva de shutdown registrada. Próxima 10B4c: cadeia integrada/coleta sustentada antes de 10C; aceite global 10B aberto. Publicação/CI e desempenho pendentes. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.
