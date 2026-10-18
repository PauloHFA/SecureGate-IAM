# Evidências — Etapa 05, entrega 5A

- Data: 18/10/2026, America/Sao_Paulo.
- Branch: `feat/policy-foundation`, baseada no commit `9f924f3` da 4D2.
- Commit: este registro acompanha o commit da entrega 5A, consultável com `git log -- docs/evidencias/etapa-05a.md`. Push, PR e CI remota desta versão ainda pendentes.
- Resultado: políticas JSONB versionadas, administração/auditoria transacional e simulação administrativa de decisões por papéis implementadas. A etapa 5 segue com ABAC/risco, MFA e integração pendentes.

## Ambiente e execução

Windows, JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 3.5.16. PostgreSQL 16.15 e Vault 1.21.4 em Testcontainers para o build completo, com credenciais sintéticas. Nenhuma nova dependência externa; migração V5 adiciona políticas ao schema iam_policy.

```powershell
.\mvnw.cmd -B -ntp spotless:apply
# Em cópia temporária isolada dos arquivos versionáveis:
.\mvnw.cmd -B -ntp clean verify
```

O build isolado evita a interferência da IDE em `target` observada nas entregas anteriores. Log em `.firecrawl/etapa5a-isolated-verify.log`, localização da cópia em `.firecrawl/etapa5a-validation-path.txt` e relatórios em `.firecrawl/etapa5a-reports/`; esses artefatos são ignorados pelo Git.

**BUILD SUCCESS: 243 testes, zero falhas, erros ou ignorados**, em aproximadamente 84 segundos. São 45 novos casos: 26 de domínio, quatro de aplicação e 15 de integração HTTP/PostgreSQL, além dos 198 anteriores. Formatação, arquitetura e limites JaCoCo aprovados. SHA-256 dos fontes/scripts/configurações conferido contra o workspace sem divergência; JAR validado copiado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`.

Links locais revisados, exemplos PowerShell do guia de políticas analisados pelo parser e `git diff --check` aprovado. Busca dos segredos reais de `.env` e dos arquivos AppRole nos arquivos versionáveis não encontrou exposição.

## Aceite coberto

- Políticas persistidas como JSONB, com nome único, prioridade, ativação e versão; API devolve documento como objeto JSON e respostas no-store.
- Cadastro, leitura, paginação, substituição e desativação sob bootstrap. Administração desabilitada por padrão continua bloqueando as novas rotas; credencial ausente/incorreta com bootstrap ativo retorna 401.
- Documento v1 restrito a target, efeito, papéis e limites. Propriedades desconhecidas, expressão SpEL, efeito/versão inválidos, coerção de schemaVersion textual, papel inválido e restrições incompatíveis são rejeitados com Problem Details sanitizado.
- Testes parametrizados cobrem as 16 combinações entre os quatro efeitos, em ordens diferentes e prioridades opostas. DENY prevalece sobre ALLOW, ALLOW_LIMITED e MFA, independentemente da prioridade.
- Sem correspondência, regras desabilitadas, target diferente ou papéis incompatíveis resultam em DENY. Target concreto é obrigatório no pedido; wildcard inteiro só pode aparecer na regra.
- Escopos de regras limitadas são intersectados com o pedido e vale o menor TTL. Interseção vazia nega; exigência de MFA preserva limites e retorna mfaRequired=true, sem comprovar o fator.
- A avaliação retorna motivos e IDs/versões das regras participantes, com ordenação determinística, sem documentos completos. Domínio copia coleções de entrada/saída.
- Integração HTTP consulta papéis atuais do banco: atribuição direta e herança por grupo permitem a mesma regra; remoção impede nova correspondência. Identidade bloqueada ou inexistente resulta em DENY/IDENTITY_INACTIVE.
- Limite de 1000 candidatos é testado no serviço: exceder resulta em DENY/POLICY_LIMIT_EXCEEDED, sem avaliar um subconjunto permissivo.
- Edição concorrente da mesma versão produz um 200 e um 409, versão final 1 e apenas um evento de edição. Nome duplicado retorna 409; política inexistente, 404.
- Falha simulada de auditoria causa 500 sanitizado e rollback tanto da criação quanto da substituição. Eventos normais contêm ator/correlação/versão/efeito/estado, sem documento completo.
- Regra inválida inserida diretamente no banco para o teste interrompe a avaliação com 500; não é ignorada e não produz ALLOW.
- Regressões OAuth/OIDC, migrações V1–V5, formatação e arquitetura permanecem no build obrigatório. JaCoCo inclui Policy com o mesmo mínimo de 80% por pacote de domínio/aplicação.

## Limites

Não foi modificado `.env`, iniciado servidor Java persistente ou aplicado V5 ao banco persistente do Compose. V5 é aplicada nos bancos temporários dos testes; será aplicada ao ambiente local quando a aplicação iniciar.

O motor é usado somente na simulação administrativa. Não altera login, emissão, escopos ou TTL de tokens existentes. Não há sinais ABAC/risco, fator TOTP, recuperação, step-up ou Outbox nesta entrega. Leituras atuais dos contextos não prometem snapshot imutável para uma operação futura; os pontos de aplicação da 5D deverão revalidar o contexto. Não se declara a etapa 5 concluída. Push, revisão e CI remota do código posterior à etapa 2 continuam pendentes.

Referências: [ADR-010](../adr/010-politicas-e-tabela-de-decisao.md), [guia de políticas](../runbooks/politicas-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
