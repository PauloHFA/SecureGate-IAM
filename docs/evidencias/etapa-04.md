# Evidências — Etapa 04, entrega 4A

- Data: 18/10/2026, America/Sao_Paulo.
- Branch: `feat/vault-transit`, baseada no commit `48632e4` da etapa 3.
- Commit: este registro acompanha o commit da entrega 4A, consultável com `git log -- docs/evidencias/etapa-04.md`. Push, PR e CI remota desta versão ainda pendentes.
- Resultado: base criptográfica Transit/JWKS implementada e validada localmente. A etapa 4 permanece em andamento; os fluxos OAuth/OIDC não estão implementados nesta entrega.

## Ambiente e execução

Windows, Oracle JDK 21.0.8, Maven Wrapper 3.9.11 e Docker Desktop com containers Linux. Spring Boot 3.5.16, Spring Security OAuth2 JOSE 6.5.11 e Nimbus JOSE JWT 9.37.4 conforme dependências resolvidas pelo parent. Vault 1.21.4 e PostgreSQL 16.15 em Testcontainers, com portas aleatórias e credenciais sintéticas. Os serviços e dados do Compose local não foram modificados pelos testes.

```powershell
.\mvnw.cmd -B -ntp spotless:apply clean verify
.\mvnw.cmd -B -ntp spotless:apply verify
```

O segundo build inclui as últimas asserções de bloqueio do JWKS quando desabilitado e rejeição de um `kid` solicitado que não corresponde à chave ativa. Ambos passaram. Total final: **134 testes, zero falhas, erros ou testes ignorados**. A entrega adiciona 23 casos de adaptador e 5 de integração Transit aos 106 testes da etapa 3.

Logs locais ignorados: `.firecrawl/etapa4-clean-verify.log` e `.firecrawl/etapa4-verify-final.log`. Relatórios reproduzíveis em `libs/security-events/target/surefire-reports/`, `iam-core/target/surefire-reports/` e `iam-core/target/failsafe-reports/`. JaCoCo continua exigindo 80% de cobertura de linhas por pacote de domínio/aplicação de Identity/Access; esse limite não mede todo o novo adaptador criptográfico.

## Aceite da entrega 4A

- Porta `SigningKeys` sem acesso a material privado; integração `JwtEncoder` pronta para uso pelo Authorization Server.
- Login AppRole usando arquivos RoleID/SecretID e política real `infra/vault/iam-core.hcl`; nenhum token root configurado no adaptador.
- JWKS HTTP condicionado à ativação explícita, sem sessão, sem campos privados, com correlação e sem abertura das rotas administrativas ou do token endpoint.
- JWT RS256 remoto verificado pela API JCA `SHA256withRSA` e por `NimbusJwtDecoder` consumindo o JWKS via HTTP.
- Rotação v1 → v2 preserva verificação de ambos os tokens; assinatura com uma versão já selecionada continua correspondente ao seu `kid` após a rotação.
- Validador de teste rejeita expiração, issuer/audiência incorretos, payload ou assinatura alterados e `alg=none`. Encoder rejeita algoritmo diferente de RS256 e `kid` incompatível.
- Identidade AppRole recebe 403 ao tentar rotacionar, exportar ou excluir a chave. Um SecretID revogado não autentica um novo adaptador.
- Desligar o Vault impede novas assinaturas. A chave pública anteriormente obtida ainda verifica um token existente. Após expirar o cache, JWKS retorna 503 sanitizado com correlação, `Retry-After` e `no-store`.
- Adaptador testado para timeout, saturação de concorrência, interrupção de thread, sessão expirada/revogada, arquivo ausente, cache, metadados inseguros e assinatura malformada.
- Timers de chamadas e contador de saturação possuem tags limitadas a operação/resultado; não expõem credenciais.
- Build completo inclui arquitetura, formatação, migrações existentes e verificações de cobertura.
- Sintaxe do script PowerShell e whitespace do diff verificados; documentação e arquivos versionáveis revisados para preservar os segredos locais.

## Pendências e limites

**4B:** integrar Spring Authorization Server com persistência JDBC de clientes/autorizações e Client Credentials, definindo issuer, audiência, escopos e TTL. **4C:** login, Authorization Code, PKCE e consentimento. **4D:** OIDC e ciclo de tokens, incluindo detecção de reuso de refresh, revogação e introspecção.

Claims de usuário/sessão nos testes são sintéticas e não representam autorização de produção. Nenhum cliente OAuth ou endpoint de emissão foi criado. A retenção mantém todas as versões públicas existentes; remoção exige política vinculada aos TTLs futuros. Renovação de leases, TLS operacional e produção Vault persistente continuam conforme o roteiro; o Vault dev perde suas chaves no reinício.

Não foi iniciado um processo Java persistente para esta validação. Os testes sobem o servidor HTTP completo com banco e Vault efêmeros. A CI remota das etapas 3/4A continua pendente de publicação.

Referências: [ADR-005](../adr/005-transit-e-jwks.md), [runbook Transit](../runbooks/transit-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
