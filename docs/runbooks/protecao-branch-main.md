# Runbook — Proteção de Branch e Governança da `main`

- **Público:** Engenheiros de Plataforma, Líderes Técnicos e Administradores de Repositório
- **Objetivo:** Definir as regras de proteção de branch para a `main`, especificando checks obrigatórios, políticas de aprovação e integridade de supply chain.
- **Repositório:** `PauloHFA/SecureGate-IAM`

---

## 1. Diretrizes de Proteção da Branch Principal (`main`)

A branch `main` representa o código em estado pronto para produção (*release-ready*). O envio direto de commits (*direct push*) é estritamente proibido. Toda alteração deve ser submetida via Pull Request (PR) e validada pela esteira de CI.

### Regras de Proteção de Branch (GitHub Branch Protection Rules):

1. **Exigência de Pull Request Obrigatório:**
   - Opção: `Require a pull request before merging`.
   - Aprovações mínimas: Pelo menos **1 revisão aprovada** por mantenedor do projeto (`Require approvals: 1`).
   - Bloqueio de auto-aprovação de PRs.
   - Descarte de aprovações anteriores quando novos commits forem adicionados (`Dismiss stale pull request approvals when new commits are pushed`).

2. **Exigência de Checks de Status com Sucesso (`Require status checks to pass before merging`):**
   Os seguintes jobs devem ter status **Pass** antes de qualquer merge ser autorizado:
   - `Java 21 / Maven verify`: Validação de compilação, Spotless format, suíte de testes unitários e de integração com Testcontainers.
   - `CodeQL Analyze (Java) (java-kotlin)`: Varredura estática SAST de segurança com suítes `security-extended` e `security-and-quality`.
   - `Container Build & Vulnerability Scan`: Compilação das imagens Docker multi-stage e varredura de vulnerabilidades Trivy (zero tolerância a vulnerabilidades `CRITICAL` e `HIGH`).
   - `GitGuardian Security Checks`: Varredura em tempo real contra vazamento de segredos, tokens e credenciais nos commits.

3. **Exigência de Branches Atualizadas (`Require branches to be up to date before merging`):**
   - O PR deve ser rebaseado ou atualizado com a `main` antes do merge para assegurar que nenhum conflito silencioso seja introduzido.

4. **Linearidade do Histórico Git (`Require linear history`):**
   - Todos os merges devem preservar histórico limpo e rastreável. Merges convencionais de PR ou Squash-and-merge são permitidos; *force pushes* são permanentemente bloqueados.

5. **Bloqueio de Deleção e Força Bruta:**
   - `Do not allow bypassing the above settings`.
   - `Do not allow force pushes` (proibição de `--force` ou `--force-with-lease` na `main`).
   - `Do not allow deletions` (proibição de apagar a branch `main`).

---

## 2. Governança de Automações e Supply Chain

1. **Pinning de GitHub Actions:**
   - 100% das GitHub Actions utilizadas em `.github/workflows/*.yml` devem ser fixadas por commit hash SHA-1 de 40 caracteres com o comentário indicando a versão semântica (ex: `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`).
   - Proibido o uso de tags mutáveis (`@main`, `@v1`, `@v2`) em fluxos produtivos.

2. **Fronteira Rígida de Publicação no GHCR:**
   - O workflow `.github/workflows/release.yml` só pode ser executado em eventos de criação de tag de versão (`v*`, ex: `v1.0.0`) ou disparo manual (`workflow_dispatch`).
   - O workflow de release **nunca** deve conter o gatilho `pull_request`, evitando que builds não auditados de PR publiquem imagens oficiais no GitHub Container Registry.

3. **Permissões Mínimas de Tokens de CI (`GITHUB_TOKEN`):**
   - `ci.yml`: `contents: read`, `pull-requests: read`.
   - `codeql.yml`: `actions: read`, `contents: read`, `security-events: write`.
   - `release.yml`: `contents: read`, `packages: write`.
