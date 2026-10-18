# Evidências — Etapa 00

- Data: 18/10/2026, America/Sao_Paulo.
- Execução: Codex no ambiente local do projeto.
- Branch: `docs/plano-de-implementacao`.
- Commit: registro incluído no commit da fundação; consultar `git log -- docs/evidencias/etapa-00.md`. Sem publicação remota nesta entrega.
- Resultado: etapa 0 concluída localmente; build e testes passaram.

## Ambiente

| Componente | Valor observado |
| --- | --- |
| Sistema | Windows 11 amd64, PowerShell |
| JDK | Oracle Java 21.0.8 |
| Maven do Wrapper | 3.9.11 |
| Wrapper | 3.3.4, `only-script` |
| Spring Boot parent | 3.5.16 |
| Spring Cloud BOM | 2025.0.3 |

Versões de bibliotecas e fontes de compatibilidade estão no [ADR-001](../adr/001-stack-e-organizacao.md).

## Entregáveis

- POM agregador com três módulos: `security-events`, `iam-core` e `policy-enforcer`.
- Dependências e plugins centralizados no parent/BOMs.
- Maven Wrapper oficial para Windows e Unix, com Maven fixado.
- Maven Enforcer exige JDK 21 e Maven compatível.
- Spotless verifica formatação durante `validate`; correção é um comando explícito.
- `.editorconfig`, `.gitattributes`, `.gitignore` e `.github/CODEOWNERS`.
- Estrutura inicial para infraestrutura e testes de carga, com estado planejado documentado.
- Contrato Java inicial `SecurityEventType`, sem dependência de Spring.
- README com pré-requisitos e comandos reproduzíveis.

## Comandos e resultados

```powershell
.\mvnw.cmd -version
.\mvnw.cmd -B -ntp spotless:apply verify
.\mvnw.cmd -B -ntp clean verify
```

O primeiro comando confirmou Maven 3.9.11 e Java 21.0.8. O segundo aplicou o formato inicial e validou o reactor. A verificação final `clean verify` foi executada em uma cópia isolada dos fontes, em diretório temporário com espaço no caminho, sem copiar `.git`, PDF, cache de pesquisa ou diretórios `target`.

A cópia isolada utilizou o cache Maven do usuário; não foi uma simulação de rede vazia nem um clone remoto das mudanças ainda não publicadas. A distribuição Maven foi baixada pelo Wrapper na preparação. Não foi necessário instalar os módulos do projeto no repositório Maven local.

| Projeto | Resultado final |
| --- | --- |
| SecureGate IAM, agregador | SUCCESS |
| Security Events | SUCCESS |
| IAM Core | SUCCESS |
| Policy Enforcer | SUCCESS |

O Surefire executou **20 casos, com 0 falhas, 0 erros e 0 ignorados** na biblioteca `security-events`. Os módulos de serviço ainda não possuem testes próprios porque contêm apenas a fronteira de pacote, sem comportamento implementado.

Os casos validam as seis routing keys previstas no documento, rejeitam tipos ausentes/malformados, espaços, letras maiúsculas e curingas de binding, e verificam o limite de 128 caracteres. Os três JARs foram gerados com sucesso.

## Artefatos

- Relatório de testes: `libs/security-events/target/surefire-reports/` após execução local.
- JARs: `target/` de cada módulo, ignorados pelo Git.
- Log local da execução isolada: `.firecrawl/etapa0-clean-verify.log`.
- Caminho local da cópia isolada: `.firecrawl/etapa0-validation-path.txt`.
- Wrapper Unix registrado com modo Git `100755`; conteúdo com final de linha LF.

## Limitações e próxima etapa

Bootstrap Spring Boot, endpoints, persistência, OAuth, assinatura Transit, consumidores e envelope de eventos ainda não estão implementados. Os JARs de serviço não são executáveis. Docker e CI não fazem parte desta validação.

A execução do Wrapper Unix ainda não foi testada em Linux/macOS. A etapa 2 adicionará CI em Linux. A cobertura mínima de domínio/aplicação será configurada na etapa 3, conforme o roteiro.

Próxima entrega: etapa 1, infraestrutura local Docker Compose com versões verificadas, healthchecks, topologia RabbitMQ e inicialização do Vault.
