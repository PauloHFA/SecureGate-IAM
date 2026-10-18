# ADR-001 — Stack e organização

- Status: aceito.
- Data: 18/10/2026.
- Origem: documento de arquitetura v0.1 e confirmação do responsável pelo projeto.

## Contexto

O SecureGate IAM precisa implementar OAuth/OIDC, políticas contextuais, persistência transacional, eventos de segurança e integração com Vault, com entregas incrementais e verificáveis.

## Decisão

Usar Java 21, Spring Boot e Spring Authorization Server, com Maven Wrapper e POM agregador na raiz. Criar os módulos `iam-core`, `policy-enforcer` e `libs/security-events`. Adicionar o gateway apenas na etapa opcional.

O `iam-core` será um monólito modular com os contextos Identity, Access, Policy, Authorization, Audit e Session. Cada contexto terá `domain`, `application`, `infrastructure` e `presentation`.

As dependências seguem `presentation → application → domain` e `infrastructure → application/domain`. O domínio não depende de Spring, JPA ou adaptadores. Contextos se comunicam por portas e eventos, sem acessar repositórios internos uns dos outros. ArchUnit verificará essas regras.

Usar PostgreSQL com Flyway, Redis, RabbitMQ e HashiCorp Vault. A biblioteca compartilhada contém contratos, sem entidades JPA ou regras internas dos serviços.

## Versões

Java 21 está definido. O PDF propõe Spring Boot 3.3+, PostgreSQL 16, Redis 7 e RabbitMQ 3.13. Essas referências não confirmam suporte atual.

Na etapa 0, verificar documentação oficial e compatibilidade entre Spring Boot, Authorization Server e Spring Cloud Vault. Registrar versões exatas e suporte antes de criar POMs e Compose. Uma mudança de versão principal com impacto arquitetural exige novo ADR. Não usar imagens com tag `latest`.

### Baseline verificada na etapa 0

| Componente | Versão / gerenciamento |
| --- | --- |
| Java | 21; ambiente de validação Oracle JDK 21.0.8 |
| Maven | 3.9.11 pelo Wrapper |
| Maven Wrapper | 3.3.4, distribuição `only-script` |
| Spring Boot | 3.5.16, parent Maven |
| Spring Cloud | BOM 2025.0.3 |
| Authorization Server | 1.5.8, gerenciado pelo Boot; integração na etapa 4 |
| Spring Cloud Vault | 4.3.3, gerenciado pelo Cloud; integração posterior |
| JUnit Jupiter | 5.12.2, gerenciado pelo Boot |
| Maven Compiler / Surefire | 3.14.1 / 3.5.6, gerenciados pelo Boot |
| Maven Enforcer | 3.6.3 |
| Spotless / Google Java Format | 3.10.4 / 1.25.2 |
| Flyway | 11.7.2, gerenciado pelo Boot; PostgreSQL habilitado na etapa 2 |
| Testcontainers | 1.21.4, gerenciado pelo Boot |
| ArchUnit | 1.5.1, fixado na etapa 2 |
| Bouncy Castle | `bcprov-jdk18on` 1.86, necessário ao Argon2id na etapa 3 |
| JaCoCo | 0.8.15, limite por pacote de domínio/aplicação na etapa 3 |
| Spring Security OAuth2 JOSE / Nimbus JOSE JWT | 6.5.11 / 9.37.4, resolvidos pelo Boot na entrega 4A |

A documentação de [requisitos do Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html) inclui Java 21 e Maven 3.9.11 nas faixas compatíveis. A [matriz oficial Spring Cloud](https://github.com/spring-cloud/spring-cloud-release/wiki/Supported-Versions) associa o release train 2025.0 a Boot 3.5 e Vault 4.3. As versões exatas acima foram confirmadas nos POMs publicados no Maven Central; usar os BOMs, sem sobrescrever versões transitivas isoladamente.

Compatibilidade de versão não substitui revisão do ciclo de suporte e de segurança antes de releases. As versões das imagens PostgreSQL, Redis, RabbitMQ e Vault serão fixadas e verificadas na etapa 1. O primeiro build não instala nem testa essas integrações.

## Consequências

- O Wrapper permite executar o build sem instalar Maven globalmente.
- Testes unitários executam sem infraestrutura externa; integração usa Docker e Testcontainers.
- A persistência OAuth pode começar com JDBC fornecido pelo framework, como mitigação prevista no PDF. JPA customizado exige justificativa e testes de equivalência.
- A v1 não inclui painel administrativo completo, federação externa ou WebAuthn.

## Alternativas

NestJS e Gradle eram alternativas do documento original. A escolha aprovada é Java/Spring Boot com Maven.
