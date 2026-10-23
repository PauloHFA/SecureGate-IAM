# ADR-067 — Imagens Multi-stage Java 21 sem Privilégios e Perfil Compose Full

- Data: 23/10/2026.
- Estado: implementada na Etapa 11A; validada localmente.

## Contexto e requisitos

A Etapa 11 estabelece os critérios de empacotamento, entrega e hardening para fechamento da versão 1.0.0 do SecureGate IAM. A primeira subdivisão (**11A**) exige:
1. Imagens Docker oficiais *multi-stage* para os dois microsserviços do projeto (`iam-core` e `policy-enforcer`), utilizando Java 21 com versões e digest/tags fixadas.
2. Execução obrigatória com usuário sem privilégios (*non-root*).
3. Configuração de probes e healthchecks no nível do container.
4. Suporte nativo ao perfil `full` no `infra/docker-compose.yml`, permitindo inicializar a stack inteira (banco, cache, mensageria, cofre e os dois microsserviços) de forma determinística e com dependências saudáveis.
5. Verificação da stack através de smoke test de inicialização abrangendo saúde, OIDC Discovery e JWKS.

## Decisão

1. **Estratégia de Construção Multi-stage:**
   - **Estágio 1 (Builder):** Utiliza imagem base `eclipse-temurin:21-jdk-alpine`, copia descritores Maven, pré-carrega dependências com caching de camadas e compila os JARs executáveis sem executar suítes de teste demoradas no build da imagem (`-DskipTests`).
   - **Estágio 2 (Runtime):** Utiliza imagem base minimalista `eclipse-temurin:21-jre-alpine`, cria usuário e grupo dedicado `securegate` (UID 10001, GID 10001), instala utilitários essenciais de diagnóstico (`curl`, `tzdata`) e copia exclusivamente o artefato JAR gerado com permissões restritas.

2. **Ergonomia e Segurança do Runtime JVM:**
   - Adicionados os parâmetros JVM `-XX:+UseContainerSupport` e `-XX:MaxRAMPercentage=75.0` no `ENTRYPOINT`, garantindo que a JVM respeite os limites de CPU e memória cgroup definidos pelo container.
   - Definida instrução `USER 10001:10001`, eliminando execução como superusuário (`root`).

3. **Orquestração Declarativa com Compose `full`:**
   - Configurado o perfil `profiles: [full]` para os serviços `iam-core` e `policy-enforcer` no `infra/docker-compose.yml`.
   - Cadeia estrita de dependências:
     - `iam-core` aguarda `postgres: service_healthy`, `redis: service_healthy`, `rabbitmq: service_healthy`, `vault: service_healthy` e `vault-init: service_completed_successfully`.
     - `policy-enforcer` aguarda `redis`, `rabbitmq`, `vault`, `vault-init` e `iam-core: service_healthy`.
   - Segredos e AppRoles são montados como volumes somente-leitura (`:ro`) sob `/etc/securegate/`, evitando a injeção de tokens estáticos em variáveis de ambiente da imagem.

4. **Healthcheck e Probes de Contrato:**
   - O container `iam-core` declara `HEALTHCHECK` consultando o endpoint de readiness do Spring Boot Actuator (`/actuator/health/readiness`).
   - Criado o script `infra/scripts/Test-FullStack.ps1` e o teste automatizado `FullStackSmokeIT.java` para validação contínua da integridade estrutural das imagens e dos endpoints OIDC/JWKS.

## Consequências

- A stack completa pode ser inicializada localmente ou em homologação com um único comando:
  `docker compose -f infra/docker-compose.yml --profile full up -d`.
- Elimina vulnerabilidades associadas à execução como root nos containers.
- Reduz o tamanho final das imagens para menos de ~250 MB (JRE Alpine).
- Prepara o projeto para os scans de vulnerabilidades e automações de CI/CD da Etapa 11B.
