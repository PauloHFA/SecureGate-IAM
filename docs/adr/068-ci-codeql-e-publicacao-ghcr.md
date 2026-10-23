# ADR-068 — Automação de CI/CD, Análise CodeQL e Publicação no GHCR

- Data: 23/10/2026.
- Estado: implementada na Etapa 11B; validada localmente.

## Contexto e requisitos

A subdivisão **11B** estabelece a esteira contínua de qualidade e segurança (*DevSecOps*) e a automação de entrega de pacotes e imagens para a versão 1.0.0 do SecureGate IAM:
1. Integrar análise estática avançada de vulnerabilidades de código via **GitHub CodeQL** para a stack Java 21.
2. Adicionar verificação e scan de segurança das imagens de container (`iam-core` e `policy-enforcer`) contra vulnerabilidades conhecidas (CVEs) em SO e bibliotecas.
3. Configurar a esteira de publicação oficial no **GitHub Container Registry (GHCR)** com versionamento semântico (*SemVer*).
4. **Regra de ouro de governança:** O fluxo de Pull Request deve validar os testes e escanear imagens, mas **NUNCA** publicar imagens de PR como se fossem releases oficiais. A publicação é restrita a tags versionadas (`v*`) ou acionamentos manuais autorizados.
5. Fixação de ações (*Action Pinning*) por commit SHA-1 para mitigar riscos de ataque à cadeia de suprimentos (*supply chain*).

## Decisão

1. **Análise Estática de Segurança com CodeQL ([`.github/workflows/codeql.yml`](../../.github/workflows/codeql.yml)):**
   - Configurado job `analyze` com matriz `java-kotlin`, permissão estrita `security-events: write` e conjunto de regras `security-extended,security-and-quality`.
   - Execução em pushes na `main`, PRs e agenda semanal (cron).

2. **Verificação de Build e Scan de Vulnerabilidades ([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)):**
   - Além do job de compilação, formatação Spotless e testes (`Java 21 / Maven verify`), adicionado o job `container-build-scan`.
   - Constrói localmente as imagens `iam-core` e `policy-enforcer` com Buildx e executa o scanner **Aquasecurity Trivy** procurando severidades `CRITICAL` e `HIGH` em bibliotecas e camadas do sistema operacional.
   - O job valida que as imagens são construíveis e seguras sem enviá-las a nenhum registry.

3. **Esteira de Publicação no GHCR ([`.github/workflows/release.yml`](../../.github/workflows/release.yml)):**
   - Gatilho restrito: evento `push` de tags que correspondam ao padrão `v*` (ex.: `v1.0.0`) e `workflow_dispatch`.
   - Ausência deliberada de gatilho `pull_request`, garantindo que imagens de desenvolvimento/PR nunca sobrescrevam tags oficiais no registry.
   - Autenticação nativa com `ghcr.io` usando `GITHUB_TOKEN` com escopo `packages: write`.
   - Geração de metadados SemVer com `docker/metadata-action` (`{{version}}`, `{{major}}.{{minor}}` e `latest`).
   - Publicação das imagens `ghcr.io/<owner>/securegate-iam-core` e `ghcr.io/<owner>/securegate-policy-enforcer`.

4. **Validação Automatizada de Governança ([`CiReleasePipelineIT.java`](../../integration-tests/src/test/java/com/securegate/e2e/CiReleasePipelineIT.java)):**
   - Teste automatizado que confere a integridade dos arquivos YAML de workflow, a ausência de publicação indevida em PRs, a presença dos jobs de CodeQL e Trivy, e o pinning de ações com SHAs de 40 caracteres hexadecimais.

## Consequências

- O processo de lançamento de novas versões torna-se totalmente automatizado e reproduzível através da criação de tags Git.
- O repositório ganha visibilidade contínua de segurança na aba *Security* do GitHub (CodeQL e dependências).
- Protege a integridade do ecossistema contra dependências ou imagens corrompidas.
