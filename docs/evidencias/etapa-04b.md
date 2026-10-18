# Evidências — Etapa 04, entrega 4B

- Data: 18/10/2026, America/Sao_Paulo.
- Branch: `feat/oauth-client-credentials`, baseada no commit `ad1a4bf` da 4A.
- Commit: este registro acompanha o commit da entrega 4B, consultável com `git log -- docs/evidencias/etapa-04b.md`. Push, PR e CI remota desta versão ainda pendentes.
- Resultado: Spring Authorization Server, persistência JDBC e Client Credentials implementados e validados localmente. A etapa 4 continua em andamento.

## Ambiente e comando

Windows, Oracle JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 3.5.16 e Spring Authorization Server 1.5.8 gerenciado pelo parent. Testcontainers 1.21.4 com PostgreSQL 16.15 e Vault 1.21.4, portas aleatórias e credenciais sintéticas.

As tentativas de build na pasta do projeto apresentaram classes internas ausentes e erro intermitente ao criar diretórios em `target`. O build final foi executado em uma cópia temporária isolada dos arquivos versionáveis, sem alterar a configuração da IDE. SHA-256 dos fontes/configurações/scripts foi comparado com o workspace após o build, sem diferenças.

```powershell
# Na cópia isolada do código, com o mesmo Wrapper e as mesmas versões:
.\mvnw.cmd -B -ntp spotless:check clean verify
```

**BUILD SUCCESS: 150 testes, zero falhas, erros ou ignorados.** A suite inclui os 134 testes anteriores, nove casos de serviço/validação de clientes, seis de integração OAuth e um novo caso que confirma a ausência de clientes em memória/encoder/decoder quando OAuth está desabilitado.

O comando usual na raiz continua sendo `.\mvnw.cmd -B -ntp clean verify`. O log do build final está em `.firecrawl/etapa4b-isolated-verify.log`; o caminho da cópia está no arquivo local `.firecrawl/etapa4b-validation-path.txt`. Relatórios foram copiados para `.firecrawl/etapa4b-reports/`, e o JAR validado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`. Esses arquivos são ignorados pelo Git. Não foi iniciado processo Java persistente.

## Aceite 4B

- Migrações V1–V3 aplicadas em bancos novos, com validação Flyway e reaplicação idempotente.
- Repositórios oficiais JDBC para clientes, autorizações e consentimentos em `iam_authorization`. Novas instâncias recuperam os clientes, autorizações e consentimentos gravados.
- Cadastro administrativo exige Bearer bootstrap; consulta não retorna segredo nem hash. O segredo de 256 bits é entregue uma vez e armazenado como Argon2id compatível com o autenticador do framework.
- Duplicidade retorna conflito. Duas requisições concorrentes criam somente um cliente e um evento de auditoria.
- Falha na auditoria reverte o cadastro na mesma transação; schema da conexão é restaurado para `public`.
- Client Credentials por HTTP real com `client_secret_basic` retorna access token Bearer, TTL de cinco minutos, sem refresh token ou ID token.
- `NimbusJwtDecoder` verifica assinatura RS256 usando o JWKS HTTP e valida o issuer configurado. Claims conferidas: sujeito/client_id, audiência aprovada, escopo solicitado, `jti` e duração. Tokens de aplicação não recebem `roles` ou `sid`.
- Credenciais inválidas, cliente inexistente, ausência de autenticação e `client_secret_post` são rejeitados; escopo não autorizado ou ausente também é rejeitado. Grants sem suporte não emitem tokens.
- Rotas de authorize, discovery, userinfo, introspecção e revogação continuam bloqueadas. O JWT máquina a máquina não autentica na administração.
- Vault indisponível retorna JSON OAuth 503 `temporarily_unavailable`, com `Retry-After`, correlação e sem cache. Não há nova autorização persistida nem fallback de assinatura local.
- Configuração desabilitada não cria repositório em memória ou encoder/decoder JWT implícito. Auto-configurações JWT/Authorization Server do Boot foram excluídas.
- Cobertura mínima JaCoCo ampliada a Authorization. Os sete pacotes medidos de domínio/aplicação/portas atingiram 100% de linhas neste build; isso não representa cobertura de todo o serviço nem dos adaptadores.
- Arquitetura, formatação, sintaxe PowerShell, links locais e ausência das credenciais locais nos arquivos versionáveis foram verificados.

## Decisões e limites

O teste de recarga JDBC detectou a restrição de desserialização de `List.of` para a audiência. A customização usa `ArrayList`, aceita pelo framework; não ampliamos a allowlist nem habilitamos tipagem arbitrária. Os schemas SQL foram adaptados conforme a orientação oficial para PostgreSQL (`text` e `timestamptz`), preservando os mapeadores padrão.

Não há login de usuário, PKCE ou consentimento interativo (4C), nem OIDC, refresh com detecção de reuso, introspecção ou revogação (4D). Cadastro/consulta de clientes estão disponíveis; rotação de segredo, desativação e limpeza de registros ainda não. O JDBC padrão guarda tokens/metadados recuperáveis no banco e será avaliado nas etapas de desempenho/hardening. Nenhuma meta de carga foi medida nesta entrega.

Os testes usam apenas dados sintéticos e não alteraram os serviços/bancos do Compose local. O `.env` não precisou mudar. Publicação e CI remota das etapas 3/4A/4B permanecem pendentes.

Referências: [ADR-006](../adr/006-authorization-server-client-credentials.md), [guia Client Credentials](../runbooks/oauth-client-credentials-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
