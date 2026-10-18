# Evidências — Etapa 04, entrega 4C

- Data: 18/10/2026, America/Sao_Paulo.
- Branch: `feat/oauth-pkce-login`, baseada no commit `04d082c` da 4B.
- Commit: este registro acompanha o commit da entrega 4C, consultável com `git log -- docs/evidencias/etapa-04c.md`. Push, PR e CI remota desta versão ainda pendentes.
- Resultado: login Identity, clientes web confidenciais, Authorization Code com PKCE e consentimento implementados e validados localmente. A etapa 4 permanece em andamento, com 4D pendente.

## Ambiente e execução

Windows, Oracle JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 3.5.16, Spring Security 6.5.11 e Authorization Server 1.5.8. As versões seguem o parent/BOM existente, sem nova dependência externa. PostgreSQL 16.15 e Vault 1.21.4 em Testcontainers, com portas aleatórias e credenciais sintéticas.

Para evitar as falhas intermitentes de arquivos de compilação observadas na 4B, foi usada uma cópia temporária isolada dos arquivos versionáveis. Não alteramos configurações da IDE. O SHA-256 dos fontes, configurações e scripts foi conferido contra o workspace após o build.

```powershell
.\mvnw.cmd -B -ntp spotless:apply
# Na cópia isolada dos mesmos fontes formatados:
.\mvnw.cmd -B -ntp clean verify
```

**BUILD SUCCESS: 177 testes, zero falhas, erros ou ignorados.** A entrega acrescenta dez casos de validação do domínio de clientes web e 17 casos de integração do fluxo de navegador aos 150 anteriores. O build final completo passou em aproximadamente 63 segundos. As asserções de Identity também verificam a versão da autenticação.

O log final fica em `.firecrawl/etapa4c-isolated-verify.log`; a localização da cópia está em `.firecrawl/etapa4c-validation-path.txt`. Relatórios reproduzíveis foram copiados para `.firecrawl/etapa4c-reports/`, e o JAR validado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`. Esses artefatos são ignorados pelo Git. Não foi iniciado servidor Java persistente nem alterado o banco/Vault do Compose local.

## Aceite 4C

- Cadastro administrativo `/api/admin/oauth-clients/browser` com segredo aleatório, hash Argon2id, redirects persistidos, grant Authorization Code, PKCE obrigatório e consentimento habilitado.
- Login HTTP real usando usuários provisionados pela API Identity, e-mail normalizado e hash Argon2id. Senha incorreta, conta inexistente, LOCKED e DISABLED produzem a mesma mensagem/URL de falha, sem ecoar credenciais.
- Login e logout rejeitam POST sem CSRF. Cookie de sessão HttpOnly/SameSite=Lax; autenticação troca o identificador da sessão. Logout exige formulário CSRF e volta ao login.
- Validação de redirect por igualdade da string completa, incluindo porta de loopback. URI diferente, método PKCE ausente/plain, state ausente e escopo vazio são rejeitados antes de redirecionar para login.
- Consentimento em HTML escapado, sem cache e com CSP/referrer restritos. Leitura usa o pedido salvo no banco. State forjado, falta de CSRF, outra sessão do mesmo usuário, pedido expirado e replay de aprovação são rejeitados.
- Negar consentimento preserva o state original do cliente e retorna `access_denied`, sem código.
- Troca válida de código/verifier por JWT RS256 assinado pelo Vault, verificado por `NimbusJwtDecoder` consumindo o JWKS HTTP e validando issuer.
- Claims conferidas: UUID de usuário em `sub`, audiência do cliente, escopo aprovado, `client_id`, papéis diretos/herdados sem duplicação e `sid` UUID. TTL de access token de cinco minutos, sem ID token/refresh.
- Metadados JDBC do principal são recarregados no fluxo; não contêm senha nem hash Argon2. Suporte Jackson fica restrito ao tipo explícito de principal, sem liberar desserialização arbitrária.
- Verifier incorreto, redirect divergente, código expirado e reuso retornam `invalid_grant`. Código tem TTL de dois minutos; teste de expiração preserva a ordem válida entre emissão e expiração.
- Troca concorrente do mesmo código produz uma emissão 200 e uma rejeição 400, usando bloqueio de linha/transação PostgreSQL.
- Bloqueio, desativação e mudança real de senha pela API Identity impedem troca de código anterior e invalidam a sessão na próxima requisição do navegador.
- Suíte anterior da 4B confirma que Client Credentials continua stateless e sem claims de usuário, que a administração não aceita JWT de aplicação e que Vault indisponível continua retornando OAuth 503.
- Migrações V1–V3, arquitetura, formatação e limites JaCoCo passam; não houve nova migração nesta entrega.
- Links locais, whitespace do diff e ausência de segredos locais nos arquivos versionáveis foram verificados.

## Decisões e limites

A camada de navegador tem sua própria cadeia e CSRF explícito no consentimento porque o framework ignora seus endpoints na configuração padrão. A transação de troca de código complementa o serviço JDBC padrão, tornando seu ciclo de leitura/checagem/invalidação atômico sob concorrência. O expiry do consentimento é texto ISO-8601 para preservar a desserialização restrita de metadados.

A sessão é local à instância, com timeout por inatividade de 15 minutos. Não foi medida propagação de revogação. Logout não promete invalidar globalmente JWTs ou códigos emitidos anteriormente; tokens já emitidos permanecem verificáveis até expirar. Redis, rate limiting e eventos completos de login seguem nas etapas previstas. OIDC, clientes públicos/SPA, refresh com rotação, introspecção e revogação ficam fora da 4C. A etapa 5 definirá como políticas/risco afetam escopos e permissões; as claims `roles` não substituem esse motor.

O `.env` e os serviços persistentes locais não precisaram mudar. Publicação/revisão/CI remota do código posterior à etapa 2 continuam pendentes.

Referências: [ADR-007](../adr/007-login-pkce-e-consentimento.md), [guia PKCE](../runbooks/oauth-pkce-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
