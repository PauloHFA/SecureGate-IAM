# Evidências — Etapa 04, entrega 4D2

- Data: 18/10/2026, America/Sao_Paulo.
- Branch: `feat/oidc-discovery-userinfo`, baseada no commit `fce0d31` da 4D1.
- Commit: este registro acompanha o commit da entrega 4D2, consultável com `git log -- docs/evidencias/etapa-04d2.md`. Push, PR e CI remota desta versão ainda pendentes.
- Resultado: discovery OAuth/OIDC, ID token Transit e userinfo implementados no fluxo confidencial Code + PKCE. A publicação/revisão da etapa 4 continua pendente.

## Ambiente e execução

Windows, JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 3.5.16, Spring Security 6.5.11 e Authorization Server 1.5.8. PostgreSQL 16.15 e Vault 1.21.4 em Testcontainers com credenciais sintéticas, sem dependência ou migração adicional.

```powershell
.\mvnw.cmd -B -ntp spotless:apply
# Em cópia temporária isolada dos mesmos arquivos versionáveis:
.\mvnw.cmd -B -ntp clean verify
```

O build isolado evita a interferência da IDE em `target`, já observada nas entregas anteriores. Log em `.firecrawl/etapa4d2-isolated-verify.log`, localização da cópia em `.firecrawl/etapa4d2-validation-path.txt` e relatórios em `.firecrawl/etapa4d2-reports/`. Esses artefatos são ignorados pelo Git.

**BUILD SUCCESS: 198 testes, zero falhas, erros ou ignorados**, em aproximadamente 78 segundos. São oito novos casos de integração sobre os 190 da 4D1. Formatação, arquitetura e limites JaCoCo aprovados. SHA-256 dos fontes/scripts/configurações conferido contra o workspace sem divergência; JAR validado copiado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`.

Links locais revisados, exemplos PowerShell do guia OIDC analisados pelo parser e `git diff --check` aprovado. Busca dos segredos reais de `.env` e dos arquivos AppRole nos arquivos versionáveis não encontrou exposição.

## Aceite coberto pelos testes

- Discovery OAuth/OIDC público com issuer fixo, URLs corretas, sem criar sessão. Anuncia somente grants implementados, Basic Auth e RS256; não anuncia Device Code, Token Exchange, DPoP, mTLS, registro dinâmico ou logout OIDC. Rotas de registro/logout OIDC continuam bloqueadas.
- Cadastro web com `oidc: true` registra `openid` e preserva PKCE/consentimento/refresh opcional. Clientes antigos sem a opção continuam sem ID token, como confirma a suíte anterior.
- Fluxo HTTP real de login/consentimento/troca retorna ID token assinado pelo Vault e validado por decoder Nimbus independente consumindo JWKS HTTP. Audiência do ID token é o client ID; a do access token continua sendo a API.
- Claims verificadas: issuer, UUID em sub, nonce original, azp, sid, auth_time, TTL de cinco minutos e nome/e-mail conforme aprovação. ID token não contém roles, scope, client_id nem email_verified. Decoder rejeita audiência de outro cliente.
- Userinfo recebe somente access token atual e ativo com openid, divulga sub/name/email conforme consentimento e não cria sessão. Resposta no-store, ID token rejeitado como Bearer.
- Sem aprovação de profile/email, ID token omite essas claims e userinfo retorna somente sub. Refresh com redução de escopos também omite as claims anteriormente aprovadas.
- Renovação retorna novo ID token, preservando sub/sid/auth_time sem repetir nonce inicial. Access token anterior deixa de acessar userinfo; revogar a família impede usar o par atual.
- Conta bloqueada, senha alterada e token expirado no banco tornam userinfo 401, sem divulgar e-mail. Token adulterado, alg=none, malformado ou ausente também é rejeitado; ausência de autenticação usa desafio Bearer. Access token sem openid recebe 403.
- Nonce ausente/curto, state curto e parâmetros claims/max_age/request são rejeitados. Cliente sem opção OIDC não pode pedir openid; erro invalid_scope volta somente ao callback validado, sem código.
- Migrações V1–V4, arquitetura, formatação, cobertura e regressões das entregas anteriores permanecem no build obrigatório.

## Limites

Não se declara certificação OpenID ou suporte aos recursos não anunciados. A v1 implementa o fluxo confidencial documentado; clientes públicos, offline_access, JAR, claims/max_age e logout OIDC ficam fora desta entrega. Logout local não revoga famílias automaticamente. Revogação em outros recursos com validação offline ainda pertence à etapa 7.

O `.env`, o banco e o Vault persistentes do Compose não precisaram mudar. Não foi iniciado servidor Java persistente. Push, revisão e CI remota do código posterior à etapa 2 continuam pendentes.

Referências: [ADR-009](../adr/009-oidc-discovery-e-userinfo.md), [guia OIDC](../runbooks/oidc-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
