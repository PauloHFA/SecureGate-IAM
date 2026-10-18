# ADR-005 — Integração Transit e publicação de chaves

- Status: aceito para a entrega 4A.
- Data: 18/10/2026.
- Complementa [ADR-002](002-assinatura-jwt-vault-transit.md).

## Decisão

Dividir a etapa 4 em entregas verificáveis. A 4A entrega a assinatura remota e JWKS; a 4B integrará o Spring Authorization Server, persistência JDBC e Client Credentials. Authorization Code com PKCE, login/consentimento, OIDC e refresh com detecção de reuso terão entregas próprias antes do aceite completo da etapa.

O contexto Authorization define `SigningKeys` em `application.port`. O adaptador usa o `HttpClient` do Java 21 nas APIs HTTP oficiais do Vault. A composição fornece um `JwtEncoder` do Spring Security para integração posterior com o Authorization Server. Não há endpoint de emissão nesta entrega.

Optamos pelo cliente HTTP para os três contratos pequenos (login AppRole, leitura de chave e assinatura). Não ativamos carregamento de propriedades pelo Spring Cloud Vault nesta fase; KV, leases e renovação coordenada continuam na etapa 9. Spring Security OAuth2 JOSE e Nimbus seguem as versões transitivas gerenciadas pelo parent Boot, sem versões isoladas.

## Assinatura

1. Ler as versões públicas de `transit/keys/securegate-jwt` e escolher `latest_version`.
2. Exigir RSA de 3072 bits, `exportable=false` e `allow_plaintext_backup=false`.
3. Construir o header `alg=RS256`, `typ=JWT`, `kid=securegate-jwt-vN`.
4. Enviar o conteúdo JWS ASCII, codificado em Base64 padrão, para `transit/sign/securegate-jwt` com `key_version=N`, `hash_algorithm=sha2-256`, `signature_algorithm=pkcs1v15` e `prehashed=false`.
5. Exigir resposta `vault:vN:...`, extrair a assinatura Base64 e convertê-la em Base64URL sem padding para o JWT.

O Vault calcula o hash uma única vez. Fixar `key_version` evita divergência entre header e assinatura caso haja rotação entre leitura e assinatura. O encoder rejeita algoritmos diferentes de RS256 e pedidos explícitos de outra chave.

## Identidade, limites e falhas

O adaptador lê RoleID/SecretID de arquivos locais montados, autentica em `auth/approle/login` e guarda o token somente na memória. Antes do fim do lease, um novo login lê novamente os arquivos. Uma resposta 403 invalida a sessão; a operação falha, e uma próxima operação pode autenticar novamente. Não repetimos automaticamente uma assinatura que falhou.

A identidade da aplicação usa a política `infra/vault/iam-core.hcl`, com leitura pública e assinatura, sem rotação, exportação ou exclusão. A operação de rotação permanece separada. Não usamos o token root da stack no processo Java.

Timeout padrão de conexão/requisição: 2 segundos por chamada HTTP. Até oito chamadas HTTP simultâneas; excesso falha imediatamente. Login inicial e consulta de chaves podem acrescentar chamadas ao tempo total de uma emissão. A sincronização do refresh de sessão/cache pode aguardar outra chamada em andamento; o limite de concorrência se refere às chamadas HTTP ao Vault.

Somente HTTPS é aceito para endereços remotos. HTTP é permitido para `127.0.0.1` e `localhost`, destinados ao desenvolvimento. Redirects HTTP não são seguidos. Respostas remotas e causas de exceção não são propagadas nas falhas públicas.

As métricas internas `iam.transit.requests` (timer por operação e resultado HTTP/JSON) e `iam.transit.rejected` (contador) não usam IDs, URLs ou segredos como tags. Não há novo endpoint Actuator público.

## JWKS e rotação

`GET /oauth2/jwks` só é habilitado com `iam.transit.enabled=true`. Publica exclusivamente `kty`, `n`, `e`, `kid`, `use=sig` e `alg=RS256`. A resposta HTTP usa `Cache-Control: no-store`; o adaptador mantém um cache interno de 30 segundos, configurável entre 1 segundo e 5 minutos.

Todas as versões públicas retornadas pelo Vault permanecem publicadas. Não há trim ou exclusão automática. Assim a chave anterior sobrevive à janela dos tokens; a retenção mínima e um procedimento de remoção serão definidos com os TTLs dos fluxos OAuth. Não aumentar `min_encryption_version` de modo a bloquear a versão ainda usada pelo cache durante a troca. Depois de rotacionar, aguardar a atualização do cache e confirmar os dois `kid` no JWKS.

Durante uma indisponibilidade, JWKS pode responder a partir do cache ainda válido; depois da expiração retorna 503 com Problem Details, correlação e `Retry-After`. Assinar sempre exige uma chamada ao Vault, inclusive com chaves públicas em cache. Verificadores com material público já obtido podem continuar verificando tokens existentes.

## Validação e limites

Testcontainers usa Vault 1.21.4 e PostgreSQL isolados, com credenciais sintéticas. Os testes carregam a política real da aplicação, verificam assinatura com JCA e Nimbus via JWKS HTTP, rotação, pinagem de versão, permissões negadas e interrupção da emissão com Vault indisponível. Testes de adaptador cobrem timeout, limite de concorrência, cache, reautenticação e respostas malformadas.

O encoder recebe claims do chamador. `roles`, `sid`, emissor, audiência e TTL definitivos serão definidos e autorizados em cada fluxo OAuth; sua presença nos testes não representa um fluxo de login implementado. A configuração de validadores de emissor/audiência nos testes não constitui um resource server de produção.

## Referências

- [Vault Transit: leitura de chave e assinatura](https://developer.hashicorp.com/vault/api-docs/secret/transit).
- [Vault AppRole: login e SecretID](https://developer.hashicorp.com/vault/api-docs/auth/approle).
- [Interface JwtEncoder do Spring Security](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/jwt/JwtEncoder.html).
