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

Estado desta revisão: **10D — ajustes e investigação de capacidade / gargalos**, em `feat/load-capacity-tuning`, validada localmente. Formalização da Lei de Little ($N = X \cdot (R + Z)$), tunings configuráveis em tempo de execução para assinatura Vault Transit e pool HikariCP, geração de Dashboards HTML nativos do JMeter e relatórios visuais consolidados (`comparative-report.html` e `capacity-investigation-report.html`), runner de concorrência escalonada `Run-CapacityInvestigation.ps1` e validação descartável `CapacityInvestigationSmoke`. Conclusão formal da Etapa 10. [Procedimento e limites](evidencias/etapa-10d.md). Próxima entrega: **Etapa 11 (Entrega e hardening)**. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.
