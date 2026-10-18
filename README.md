# SecureGate IAM

Plataforma de identidade e acesso baseada em Zero Trust, com provisionamento, OAuth/OIDC, políticas contextuais, eventos de segurança e auditoria.

## Estado atual

Fundação Maven, infraestrutura local e bootstrap do `iam-core` implementados. O core inicia com Spring Boot, migra o PostgreSQL via Flyway e expõe probes de saúde. Os testes incluem arquitetura e integração com PostgreSQL temporário. A CI foi executada com sucesso no GitHub, com 40 testes aprovados; consulte as [evidências da etapa 2](docs/evidencias/etapa-02.md).

## Decisões aprovadas

- Java 21, Spring Boot e Spring Authorization Server.
- Maven com Wrapper e build multimódulo.
- Vault Transit para assinatura JWT desde a etapa de autenticação.
- PostgreSQL, Redis e RabbitMQ para dados, estado distribuído e eventos.

## Documentação

- [Roteiro de implementação e acompanhamento](docs/PLANO-DE-IMPLEMENTACAO.md)
- [ADR-001 — Stack e organização](docs/adr/001-stack-e-organizacao.md)
- [ADR-002 — Assinatura JWT com Vault Transit](docs/adr/002-assinatura-jwt-vault-transit.md)
- [Infraestrutura local: execução, credenciais e diagnóstico](infra/README.md)
- [ADR-003 — Baseline da infraestrutura](docs/adr/003-infraestrutura-local.md)
- [Execução e diagnóstico do iam-core](docs/runbooks/iam-core-local.md)
- [Evidências da etapa 2](docs/evidencias/etapa-02.md)

O roteiro deriva do documento **SecureGate IAM – Documentação e Plano de Implementação**, versão 0.1, outubro de 2026, disponível em PDF na pasta local do projeto.

## Próximo passo

Iniciar a etapa 3: Identity e Access. O [PR #4](https://github.com/PauloHFA/SecureGate-IAM/pull/4) reúne as etapas iniciais e está disponível para revisão. As evidências das etapas [0](docs/evidencias/etapa-00.md), [1](docs/evidencias/etapa-01.md) e [2](docs/evidencias/etapa-02.md) registram as validações executadas.

A v1 tem foco em API REST. Gateway é opcional; federação externa, WebAuthn e painel administrativo completo ficam para evoluções posteriores.

## Build local

Pré-requisitos: JDK 21, `JAVA_HOME` apontando para esse JDK, Docker Engine disponível e internet na primeira execução. O Wrapper baixa Maven 3.9.11. Testcontainers inicia seu próprio PostgreSQL temporário; não é necessário subir o Compose para executar `verify`.

Windows PowerShell:

```powershell
.\mvnw.cmd -version
.\mvnw.cmd -B -ntp clean verify
```

Linux/macOS:

```sh
./mvnw -version
./mvnw -B -ntp clean verify
```

O build valida JDK/Maven, verifica formatação, executa testes unitários e de integração e gera os JARs. `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar` é executável Spring Boot; `policy-enforcer` ainda não possui bootstrap. Os testes de integração falham se Docker não estiver disponível, sem serem ignorados silenciosamente.

Para executar apenas testes unitários/arquitetura sem iniciar containers, use `./mvnw test` ou `.\mvnw.cmd test`. Isso não substitui o aceite completo de `verify`. Relatórios ficam em `target/surefire-reports/` e `target/failsafe-reports/` dos módulos.

Para corrigir formatação Java, execute `.\mvnw.cmd spotless:apply` no Windows ou `./mvnw spotless:apply` no Linux/macOS. O `verify` apenas verifica a formatação, sem alterar o código.

| Módulo | Responsabilidade atual |
| --- | --- |
| `libs/security-events` | Biblioteca Java sem Spring, com validação inicial dos tipos de evento |
| `iam-core` | Aplicação Spring Boot com Actuator, segurança inicial, Flyway e testes |
| `policy-enforcer` | Módulo reservado para o consumidor; implementação na etapa 7 |

O contrato inicial `SecurityEventType` aceita segmentos minúsculos separados por ponto, até 128 caracteres, e rejeita curingas de binding (`*` e `#`). O envelope versionado e a mensageria serão implementados na etapa 6. Relatórios de teste ficam em `libs/security-events/target/surefire-reports/`.

## Infraestrutura local

Com Docker Desktop em execução, na raiz do repositório:

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\infra\scripts\Test-Local.ps1
```

PostgreSQL fica em `127.0.0.1:15432`, Redis em `127.0.0.1:6379`, RabbitMQ em `127.0.0.1:5672` com painel na porta `15672`, e Vault na porta `8200`. Credenciais são geradas em `.env` e `infra/.local/`, ignorados pelo Git.

Vault usa modo dev e perde chaves ao reiniciar. Consulte o [guia de infraestrutura](infra/README.md) para reexecutar o bootstrap, operar a stack e entender os limites do ambiente local.

## Executar o iam-core

Após o build e a subida da infraestrutura:

```powershell
.\infra\scripts\Start-IamCore.ps1
```

O script lê o `.env` local e transmite a senha por variável de ambiente ao processo Java, sem gravá-la no código ou passá-la na linha de comando. A aplicação escuta em `127.0.0.1:8080`; `-Port 18080` permite trocar a porta. As migrações serão aplicadas ao banco local na primeira inicialização.

Endpoints públicos: `GET /actuator/health`, `/actuator/health/liveness` e `/actuator/health/readiness`. Demais rotas são bloqueadas com HTTP 403 até a implementação da autenticação/API. Não há login, emissão de tokens ou provisionamento ainda.

## Arquivos locais

A pasta `.firecrawl/` contém cache local de pesquisa e extração de documentos e é ignorada pelo Git.
