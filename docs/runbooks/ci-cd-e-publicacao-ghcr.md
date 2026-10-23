# Runbook — Esteira de CI/CD, Análise CodeQL e Publicação no GHCR (Etapa 11B)

Este runbook orienta a operação, auditoria e procedimentos de release do SecureGate IAM utilizando as automações do GitHub Actions e o GitHub Container Registry (GHCR).

---

## 1. Visão Geral da Arquitetura de CI/CD e Segurança

O projeto utiliza três fluxos de automação desacoplados, seguindo o princípio do menor privilégio e segurança de cadeia de suprimentos (*Supply Chain Security*):

```text
                                  ┌─────────────────────────────┐
                                  │      Pull Request / Push    │
                                  └──────────────┬──────────────┘
                                                 │
                      ┌──────────────────────────┴──────────────────────────┐
                      ▼                                                     ▼
        ┌───────────────────────────┐                         ┌───────────────────────────┐
        │      Workflow: CI.yml     │                         │   Workflow: CodeQL.yml    │
        │ - Maven verify (Java 21)  │                         │ - SAST Java / Kotlin      │
        │ - Spotless format check   │                         │ - security-extended       │
        │ - Unit & Integration tests│                         │ - security-and-quality    │
        │ - Build container images  │                         │ - Upload SARIF alerts     │
        │ - Trivy vulnerability scan│                         └───────────────────────────┘
        └───────────────────────────┘
                      │
                      │ (Imagens descartáveis locais para scan; NENHUM push ao registry)
                      ▼

                                  ┌─────────────────────────────┐
                                  │   Git Tag Push (ex: v1.0.0) │
                                  └──────────────┬──────────────┘
                                                 │
                                                 ▼
                                  ┌─────────────────────────────┐
                                  │   Workflow: Release.yml     │
                                  │ - Autenticação com GHCR     │
                                  │ - Buildx multi-arch         │
                                  │ - Tags SemVer (1.0.0, latest│
                                  │ - Push oficial ao GHCR      │
                                  └─────────────────────────────┘
```

---

## 2. Detalhamento dos Workflows

### 2.1. Workflow de CI (`.github/workflows/ci.yml`)
- **Gatilhos:** `push` na branch `main`, `pull_request` (qualquer branch alvo) e `workflow_dispatch`.
- **Jobs:**
  - `verify`: Compila todos os módulos em Java 21, executa verificação de formatação Spotless e executa a suíte de testes unitários e de integração com cobertura de código JaCoCo.
  - `container-build-scan`: Constrói as imagens `iam-core` e `policy-enforcer` utilizando Buildx e executa o scanner de segurança **Aquasecurity Trivy** procurando vulnerabilidades `CRITICAL` e `HIGH` em dependências e bibliotecas do sistema operacional Alpine.

### 2.2. Workflow de Análise CodeQL (`.github/workflows/codeql.yml`)
- **Gatilhos:** `push` na `main`, `pull_request` para `main`, agendamento semanal (cron) e `workflow_dispatch`.
- **Escopo:** Análise estática avançada de código Java/Kotlin procurando padrões inseguros de criptografia, injeção de SQL, vazamento de memória ou caminhos de execução não sanitizados.
- **Permissões:** `security-events: write` restrito ao envio de relatórios SARIF.

### 2.3. Workflow de Release e Publicação GHCR (`.github/workflows/release.yml`)
- **Gatilhos:** Exclusivamente no `push` de tags que sigam o padrão `v*` (ex.: `v1.0.0`, `v1.0.0-rc1`) ou via disparo manual `workflow_dispatch`.
- **Regra de ouro de governança:** **NUNCA é executado em Pull Requests**. Imagens geradas em PRs não poluem o registry oficial.
- **Destino das Imagens:**
  - `ghcr.io/<owner>/securegate-iam-core:<versão>`
  - `ghcr.io/<owner>/securegate-policy-enforcer:<versão>`

---

## 3. Procedimento Operacional de Release

Para publicar oficialmente uma nova versão do SecureGate IAM:

1. **Garantir que a branch `main` esteja íntegra e com todos os checks de CI e CodeQL verdes:**
   ```powershell
   git checkout main
   git pull origin main
   ```

2. **Criar a tag semântica anotada:**
   ```powershell
   git tag -a v1.0.0 -m "Release v1.0.0 - SecureGate IAM Core e Enforcer"
   ```

3. **Enviar a tag para o repositório remoto:**
   ```powershell
   git push origin v1.0.0
   ```

4. **Acompanhar a execução do workflow de release:**
   ```powershell
   gh run watch
   ```

---

## 4. Consumo das Imagens Publicadas

Uma vez publicadas, as imagens podem ser obtidas publicamente ou via autenticação Docker:

```powershell
docker pull ghcr.io/paulohfa/securegate-iam-core:1.0.0
docker pull ghcr.io/paulohfa/securegate-policy-enforcer:1.0.0
```

Para uso no Docker Compose em ambiente de produção:
```yaml
services:
  iam-core:
    image: ghcr.io/paulohfa/securegate-iam-core:1.0.0
  policy-enforcer:
    image: ghcr.io/paulohfa/securegate-policy-enforcer:1.0.0
```
