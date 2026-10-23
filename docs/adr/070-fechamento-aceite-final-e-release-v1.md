# ADR-070: Fechamento Técnico, Aceite Final e Procedimento de Release v1.0.0

- **Status:** Aceito
- **Data:** 23/10/2026
- **Contexto:** Etapa 11D — Fechamento e Release v1.0.0
- **Decisores:** Engenharia SecureGate IAM

---

## 1. Contexto e Problema

Com a conclusão do empacotamento em containers (11A), esteira de CI com CodeQL e publicação GHCR (11B), e consolidação de hardening/ASVS (11C), fez-se necessário formalizar o encerramento do marco `v1.0.0` da plataforma SecureGate IAM. Era necessário:
1. Validar e documentar o procedimento operacional de instalação limpa (*cold start*).
2. Emitir o relatório consolidado de aceite técnico final para a versão estável `v1.0.0`.
3. Estabelecer o procedimento de criação da tag semântica `v1.0.0` e o acionamento do pipeline de publicação oficial.
4. Definir formalmente o status das extensões da Etapa 12 (API Gateway e jornada demonstrativa) em relação ao núcleo estável.

---

## 2. Decisão Arquitetural

Decidiu-se por:

1. **Homologação Final da Versão 1.0.0:**
   - Formalização do aceite técnico no documento [`docs/relatorios/aceite-final-v1.0.0.md`](../relatorios/aceite-final-v1.0.0.md), atestando o cumprimento de 100% dos requisitos mandatórios das Etapas 0 a 11.

2. **Runbook de Instalação Limpa e Tagueamento:**
   - Publicação do guia operacional [`docs/runbooks/instalacao-limpa-e-release-v1.md`](../runbooks/instalacao-limpa-e-release-v1.md), padronizando a subida da stack completa via Docker Compose com perfil `full` e execução do smoke test automatizado.

3. **Gatilho de Release no GitHub Actions:**
   - Padronização do envio da tag `v1.0.0` como o gatilho exclusivo para o workflow [`.github/workflows/release.yml`](../../.github/workflows/release.yml), publicando as imagens oficiais `ghcr.io/paulohfa/securegate-iam-core:v1.0.0` e `ghcr.io/paulohfa/securegate-policy-enforcer:v1.0.0`.

4. **Desacoplamento da Etapa 12:**
   - A Etapa 12 (Gateway e demonstração) permanece confirmada no roadmap como componente opcional de valor agregado, não bloqueando a declaração de produção da v1.0.0 dos módulos centrais.

---

## 3. Consequências

### Positivas:
- **Marco de Estabilidade:** O núcleo do SecureGate IAM alcança a primeira versão de produção estável, auditada e documentada.
- **Reprodutibilidade Assegurada:** Qualquer desenvolvedor ou operador pode clonar o repositório e subir a infraestrutura completa em minutos utilizando comandos padronizados.
- **Rastreabilidade e Governança:** Artefatos publicados no GHCR diretamente associados ao commit e à tag do Git.

### Neutras:
- O módulo de API Gateway (Spring Cloud Gateway) será tratado como projeto cliente/satélite na Etapa 12, consumindo as APIs do núcleo já estabilizadas.
