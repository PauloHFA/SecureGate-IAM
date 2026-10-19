# Evidências — Etapa 05, entrega 5B1

- Data: 18/10/2026, America/Sao_Paulo.
- Branch: `feat/abac-risk-engine`, baseada no commit `8f5666d` da 5A.
- Commit: este registro acompanha o commit da entrega 5B1, consultável com `git log -- docs/evidencias/etapa-05b1.md`. Push, PR e CI remota desta versão ainda pendentes.
- Resultado: gramática ABAC v2, cálculo determinístico de risco e cenários administrativos implementados. Coleta real de sinais fica na 5B2; a etapa 5 permanece em andamento.

## Ambiente e execução

Windows, JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 3.5.16. PostgreSQL 16.15 e Vault 1.21.4 temporários em Testcontainers para o build completo. Nenhuma dependência externa nova; V6 amplia a restrição de documento JSONB para v1/v2.

```powershell
.\mvnw.cmd -B -ntp spotless:apply
# Em cópia temporária isolada dos mesmos arquivos versionáveis:
.\mvnw.cmd -B -ntp clean verify
```

**BUILD SUCCESS: 311 testes, zero falhas, erros ou ignorados**, em aproximadamente 85 segundos. São 68 casos adicionais sobre os 243 da 5A: 46 de domínio ABAC/risco, dez de codec, um de aplicação e 11 de integração. Formatação, arquitetura e limites JaCoCo aprovados.

O build isolado evita a interferência da IDE em `target` observada anteriormente. Log em `.firecrawl/etapa5b1-isolated-verify.log`, localização da cópia em `.firecrawl/etapa5b1-validation-path.txt` e relatórios em `.firecrawl/etapa5b1-reports/`; artefatos ignorados pelo Git.

SHA-256 dos fontes/scripts/configurações conferido contra o workspace sem divergência. Relatórios copiados para a pasta de evidências local e JAR validado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`. Links locais revisados, exemplos PowerShell do guia analisados pelo parser e `git diff --check` aprovado. Busca dos segredos reais de `.env` e dos arquivos AppRole nos arquivos versionáveis não encontrou exposição.

## Aceite observado

- Gramática v2 com atributos e operadores fechados, conjunção ALL e limites de quantidade/tamanho. Script, atributo arbitrário, operador inválido, propriedade desconhecida e literal numérico não textual são rejeitados, inclusive via HTTP, sem ecoar payload.
- V1 mantém formato sem conditions e comportamento anterior. Acrescentar conditions a v1 é rejeitado. Leitura/serialização v1/v2 preserva os documentos e alterações exigem PUT/versionamento.
- Migração real de um banco temporário até V5, inserção de política v1, aplicação somente de V6 e validação do histórico; documento e versão originais preservados. Banco normal de testes aplica V1–V6.
- Pesos de dispositivo/rede/falhas/sensibilidade, score de 0–100, faixas em 29/30, 59/60 e 79/80 e reprodutibilidade conferidos por testes de relógio fixo.
- Sinais ausentes geram os maiores pesos correspondentes. Conjunto vencido, sem instante ou no futuro além da tolerância torna-se UNKNOWN/score100, com motivos e quatro sinais ausentes. Limites exatos de tempo também testados.
- Condições avaliam MATCH/NO_MATCH/INDETERMINATE. Uma regra potencialmente aplicável com condição desconhecida não é descartada para permitir por outra regra; DENY explícito mantém precedência. Uma condição comprovadamente falsa torna ALL falso mesmo com outra ausente.
- Cenários HTTP conhecidos produzem ALLOW, MFA ou DENY conforme o score. MFA mantém mfaRequired=true; combinação com ALLOW_LIMITED preserva interseção de escopos e menor TTL.
- Simulação identificada com simulation=true não altera a avaliação normal posterior. Contexto/score extras enviados a evaluate não reduzem seu risco. Identidade e papéis continuam no banco; os cenários não os sobrescrevem.
- Caminho normal usa o provider explícito de ausência, retorna simulation=false, score100 e missingSignals, sem fabricar coleta. Políticas v1 continuam podendo permitir quando não exigem condição de risco; score alto isoladamente não reescreve efeitos legados.
- Simulate exige bootstrap, cenário e valores válidos. Não há endpoint público de evidência, score pronto ou comprovação de fator.
- Suíte anterior de políticas/versionamento/rollback, OAuth/OIDC, assinatura Vault e segurança permanece aprovada. Domínio/aplicação de Policy continuam sob o mínimo JaCoCo de 80% por pacote.

## Limites e publicação

Não foram modificados `.env` ou serviços persistentes do Compose. V6 foi aplicada apenas nos bancos temporários; será aplicada ao ambiente local na próxima inicialização. Nenhum servidor Java persistente foi iniciado.

Não existe coleta real de dispositivo/rede/comportamento/recurso: isso permanece na 5B2. A janela de falhas recentes e a origem/vínculo de cada sinal deverão ser garantidas pelo coletor. Pesos são baseline sem calibração estatística. Avaliação e simulação continuam recomendações administrativas, sem aplicação em login/tokens/recursos. MFA real e aplicação ficam na 5C/5D.

A base até a 5A (`8f5666d`) foi publicada em `feat/policy-foundation` e passou na [CI do GitHub](https://github.com/PauloHFA/SecureGate-IAM/actions/runs/39159901831). Esta 5B1 possui commit consultável no histórico e ainda aguarda push/CI remota; revisão/merge permanecem pendentes.

Referências: [ADR-011](../adr/011-abac-e-risco-deterministico.md), [guia ABAC/risco](../runbooks/abac-risco-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
