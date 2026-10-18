# Evidências — Etapa 04, entrega 4D1

- Data: 18/10/2026, America/Sao_Paulo.
- Branch: `feat/oauth-refresh-lifecycle`, baseada no commit `41497c1` da 4C.
- Commit: este registro acompanha o commit da entrega 4D1, consultável com `git log -- docs/evidencias/etapa-04d1.md`. Push, PR e CI remota desta versão ainda pendentes.
- Resultado: refresh com rotação/detecção de reuso, introspecção e revogação implementados e validados localmente. OIDC permanece na 4D2.

## Ambiente e comandos

Windows, JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 3.5.16, Spring Security 6.5.11 e Authorization Server 1.5.8. PostgreSQL 16.15 e Vault 1.21.4 reais em Testcontainers, com credenciais sintéticas; nenhuma nova dependência.

```powershell
.\mvnw.cmd -B -ntp spotless:apply
# Em cópia temporária isolada dos arquivos versionáveis:
.\mvnw.cmd -B -ntp clean verify
```

**BUILD SUCCESS: 190 testes, zero falhas, erros ou ignorados**, em aproximadamente 75 segundos. São 13 novos casos de integração, além dos 177 anteriores. Formatação, arquitetura e limites JaCoCo aprovados.

A cópia isolada evita a interferência já observada da IDE nos diretórios de compilação, sem alterar suas configurações. SHA-256 dos fontes, scripts e configurações foi comparado com o workspace após o build, sem divergência. Log em `.firecrawl/etapa4d1-isolated-verify.log`, localização da cópia em `.firecrawl/etapa4d1-validation-path.txt`, relatórios em `.firecrawl/etapa4d1-reports/`. O JAR validado foi copiado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`. Artefatos são ignorados pelo Git.

## Aceite observado

- Cadastro web com opção explícita de refresh; padrão anterior sem refresh preservado. Consentimento informa prazo máximo de renovação.
- Migrações V1–V4 aplicadas em PostgreSQL real, incluindo famílias e histórico por hash.
- Primeira emissão cria a família; renovação troca ambos os tokens, preserva identidade/sid e prazo absoluto de 30 minutos. JWT verificado via JWKS inclui papéis atualizados e escopo autorizado.
- Histórico guarda SHA-256 do refresh anterior e marca consumo; o registro JDBC padrão mantém somente o refresh atual. O histórico não contém tokens em claro.
- Reuso revoga toda a família, inclusive o par mais recente. Repetição não duplica auditoria, cujos detalhes não contêm os tokens.
- Duas renovações simultâneas do mesmo token resultam em 200 e 400; a detecção de reuso invalida o token emitido pela requisição vencedora.
- Escopo ampliado e tentativa por cliente estrangeiro são rejeitados sem consumir o refresh do proprietário. Introspecção estrangeira retorna apenas `active: false`; revogação estrangeira não altera o token.
- Revogação por access token atual, refresh atual ou refresh histórico invalida toda a família. Repetição e token desconhecido retornam 200 sem duplicar o evento.
- Bloqueio e mudança real de senha impedem renovação e tornam a introspecção inativa; expiração absoluta da família impede renovação.
- Falha real de permissão de assinatura no Vault retorna 503 `temporarily_unavailable`, sem expor segredos ou consumir o refresh válido. Após restaurar a política sintética, o mesmo refresh pode ser usado.
- Introspecção/revogação exigem autenticação de cliente (401); parâmetro token ausente retorna 400. Tokens desconhecidos são inativos.
- Client Credentials mantém emissão sem refresh; introspecção detecta expiração, limita acesso ao proprietário e revogação é idempotente e auditada.
- A suíte anterior cobre login, PKCE, consentimento, código concorrente, JWTs negativos e indisponibilidade do Vault. Discovery/userinfo continuam bloqueados.
- Links locais revisados, exemplos PowerShell do novo guia analisados pelo parser e `git diff --check` aprovado. Busca dos segredos reais de `.env` e dos arquivos AppRole nos arquivos versionáveis não encontrou exposição.

## Limites e pendências

Não foi iniciado servidor Java persistente nem alterado banco/Vault do Compose. `.env` não precisou mudar. Os testes alteram somente a política de seu Vault temporário, restaurada após o cenário de falha.

Revogação é observável por introspecção; não foi medida propagação para recursos que validam JWT offline. Essa integração pertence à etapa 7. Logout local não revoga refresh automaticamente. Histórico ainda não possui limpeza automática; banco/backups armazenam o token atual pelo JDBC padrão e exigem proteção. Discovery, ID token e userinfo ficam para a 4D2. Publicação, revisão e CI remota do código posterior à etapa 2 continuam pendentes.

Consulte [ADR-008](../adr/008-refresh-rotacao-e-revogacao.md), [guia operacional](../runbooks/oauth-refresh-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
