# Documentação — SecureGate IAM

Começar pelo [guia de implementação passo a passo](GUIA-DE-IMPLEMENTACAO.md): ele reúne a sequência completa, todas as subdivisões executadas, as próximas divisões propostas e o mapa de migrações.

| Documento | Quando consultar |
| --- | --- |
| [Plano de implementação](PLANO-DE-IMPLEMENTACAO.md) | Escopo, dependências, tarefas e critérios de aceite das etapas 0–12. |
| [Guia passo a passo](GUIA-DE-IMPLEMENTACAO.md) | Ordem de construção, motivos das divisões, como validar e como registrar a próxima entrega. |
| [ADRs](adr/) | Decisões arquiteturais, alternativas e limites de cada entrega. |
| [Guias operacionais](runbooks/) | Preparação, execução, flags, testes e recuperação. |
| [Evidências](evidencias/) | Comandos executados, resultados observados, ambiente e pendências históricas. |

Estado desta revisão: código implementado até **9C3**, com **748 testes aprovados**, incluído no commit desta entrega sobre `bb48f3f`. Publicação e CI remota continuam pendentes. Próximo trabalho: **9A3**, KV do enforcer; depois hardening/aceite final global da etapa 9.

Para a entrega atual, consultar [jornada PAM integrada](runbooks/jornada-pam-integrada-local.md), [ADR-043](adr/043-aceite-integrado-pam.md) e [evidências 9C3](evidencias/etapa-09c3.md). Para infraestrutura, consultar [infra/README](../infra/README.md).

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

Revisão documental de 20/10/2026: os 43 registros de evidências de etapas estão referenciados no guia; 903 links locais foram verificados sem referências quebradas. A contagem de 748 testes corresponde ao build completo da 9C3 registrado em suas evidências, concluído às 20:46:17 UTC−03; a documentação foi validada por coerência, cobertura do histórico, links e git diff --check.
