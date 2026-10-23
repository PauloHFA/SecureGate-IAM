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

Estado desta revisão: **11A — imagens multi-stage Java 21 e perfil Compose full**, em `feat/container-images-full-compose`, validada localmente. Dockerfiles multi-stage para `iam-core` e `policy-enforcer` (`eclipse-temurin:21-jdk-alpine` builder e `eclipse-temurin:21-jre-alpine` runtime non-root `10001:10001`), `.dockerignore` sanitizado, perfil `full` no `infra/docker-compose.yml` com ordenação por healthchecks, script de smoke da stack completa `Test-FullStack.ps1` e validação automatizada `FullStackSmokeIT`. [Procedimento e limites](evidencias/etapa-11a.md). Próxima entrega: **11B (CI, CodeQL, scans e release GHCR)**. Nenhuma stack persistente alterada.

Ao implementar uma nova subdivisão, atualizar guia, plano, README, decisões aplicáveis, operação e evidências. Proposta, implementação, validação local, commit, publicação, CI e merge devem permanecer identificáveis separadamente.

O histórico das entregas anteriores e seus resultados permanece nas evidências individuais. O guia referencia também as 10B4a/b e registra suas divisões passo a passo.
