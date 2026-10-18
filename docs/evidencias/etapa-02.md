# Evidências — Etapa 02

- Data: 18/10/2026, America/Sao_Paulo.
- Base de trabalho: commit `19ce56f` das etapas 0/1.
- Publicação: commit `b1f1e3a759ff74fc7e56c21dc64f4aa7890b530e`, branch `docs/plano-de-implementacao`, [PR #4](https://github.com/PauloHFA/SecureGate-IAM/pull/4).
- Resultado: implementação, validação local e CI no GitHub aprovadas.

## Ambiente e versões

Windows 11, Oracle JDK 21.0.8, Maven Wrapper 3.9.11 e Docker Desktop com containers Linux. Mantidos Spring Boot 3.5.16 e BOM Cloud 2025.0.3.

- Flyway 11.7.2 e Testcontainers 1.21.4, gerenciados pelo Boot.
- ArchUnit 1.5.1, centralizado no parent Maven.
- PostgreSQL de integração: `postgres:16.15-alpine`, banco temporário em porta aleatória e senha aleatória.
- Failsafe 3.5.6, gerenciado pelo Boot, vinculado a `integration-test` e `verify` no reactor.

## Comandos executados

```powershell
.\mvnw.cmd -B -ntp spotless:apply clean verify
.\.firecrawl\tools\actionlint\actionlint.exe .github/workflows/ci.yml
```

O build limpo terminou com `BUILD SUCCESS` para agregador e três módulos. O formato final foi verificado pelo Spotless em `validate`. O actionlint 1.7.12 foi baixado da release oficial, conferido pelo SHA-256 publicado e não retornou erros para o workflow.

| Grupo | Casos | Falhas/erros/ignorados |
| --- | --- | --- |
| Contrato SecurityEventType | 20 | 0 / 0 / 0 |
| Regras de arquitetura: fixtures de violação e porta válida | 4 | 0 / 0 / 0 |
| Filtro de correlação/MDC | 3 | 0 / 0 / 0 |
| Arquitetura dos fontes de produção | 5 | 0 / 0 / 0 |
| Integração iam-core com HTTP e PostgreSQL real | 8 | 0 / 0 / 0 |
| **Total** | **40** | **0 / 0 / 0** |

## Comportamento verificado

- Contexto Spring Boot inicia com servidor HTTP em porta aleatória e PostgreSQL real via Testcontainers.
- Flyway aplica V1, cria os seis schemas, valida o histórico e não reaplica a migração em uma segunda chamada.
- Saúde e probes respondem UP sem detalhes do banco; readiness fora de serviço retorna 503, mantendo liveness 200.
- Rotas administrativas/de negócio não implementadas e endpoints Actuator não expostos são bloqueados com 403 e Problem Details, inclusive POST.
- Erros 400, 404, 405 e 500 usam Problem Details com correlação; 405 preserva `Allow`.
- Valores sensíveis simulados, query string e detalhes de exceção não aparecem nas respostas ou nos logs capturados dos testes negativos.
- IDs inválidos são substituídos e o MDC não vaza para a próxima requisição, inclusive quando ocorre exceção.
- Regras ArchUnit detectam as violações intencionais e permitem a colaboração por porta.
- O JAR contém bootstrap, migração e manifesto com `JarLauncher`/`Start-Class` corretos; não contém os controllers, permissões ou fixtures de teste.

## CI configurada

Workflow `.github/workflows/ci.yml`, com eventos PR, push para `main` e manual, executa `verify` em Ubuntu com JDK 21, cache Maven e relatórios Surefire/Failsafe.

Actions verificadas nos repositórios oficiais e fixadas por SHA:

| Action | Release | SHA |
| --- | --- | --- |
| checkout | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| setup-java | v6.1.0 | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` |
| upload-artifact | v7.0.2 | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |

### Execução remota aprovada

- [CI #39137410438](https://github.com/PauloHFA/SecureGate-IAM/actions/runs/39137410438), disparada pelo PR #4 no commit `b1f1e3a759ff74fc7e56c21dc64f4aa7890b530e`.
- Job `Java 21 / Maven verify` concluído com sucesso em 57 segundos em runner Ubuntu hospedado pelo GitHub.
- O log confirmou `BUILD SUCCESS`: 20 testes de contrato, 12 unitários/arquitetura e 8 de integração; zero falhas, erros ou ignorados.
- Checkout, JDK 21, Docker, build/testes e publicação do artefato `test-reports` concluíram com sucesso.
- GitGuardian Security Checks também passou no PR.
- Log remoto copiado para `.firecrawl/etapa2-ci-github.log`, ignorado pelo Git.

Esta execução valida o código publicado da etapa 2. O commit posterior de documentação registra o resultado; seus checks devem ser confirmados no PR antes do merge.

## Artefatos e limites

- Log local: `.firecrawl/etapa2-clean-verify.log`.
- Testes unitários/arquitetura: `libs/security-events/target/surefire-reports/` e `iam-core/target/surefire-reports/`.
- Integração: `iam-core/target/failsafe-reports/`.
- Aplicação empacotada: `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`.
- Instruções: [runbook do iam-core](../runbooks/iam-core-local.md).

Não houve teste separado do JAR em segundo plano: essa checagem adicional de iniciar/encerrar um processo foi bloqueada pela revisão automática da ferramenta. A inicialização da aplicação foi comprovada em processo de teste com servidor HTTP real, e o empacotamento foi inspecionado separadamente. O script de execução local foi revisado, mas não executado nesta entrega.

Camadas de negócio ainda vazias são permitidas nas regras enquanto o scaffold evolui; as fixtures comprovam a detecção de dependências proibidas. Ainda não há usuários, autenticação OAuth, tabelas de negócio, endpoints administrativos ou integração Vault/Redis/RabbitMQ no Java.

Aceite da etapa 2 concluído. O PR permanece aberto para revisão e merge posterior; a próxima implementação é Identity e Access na etapa 3.
