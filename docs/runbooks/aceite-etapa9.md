# Aceite técnico local da etapa 9 — 9D

Revisão em 20/10/2026 sobre o commit **6d08c2a**, que inclui a 9A3. Esta consolidação não modifica aplicação, ACL, schema ou stack persistente. O aceite técnico local usa o build da 9A3 e inspeção dos controles. Publicação, CI, merge e prontidão de produção são marcos separados.

## Matriz de controles e evidências

| Critério | Controle implementado e prova revisada | Resultado local |
| --- | --- | --- |
| KV IAM/enforcer | AppRole por arquivo, caminho fixo, campos permitidos, snapshot completo prioritário e token revogado. VaultKvEnvironmentPostProcessorTest e VaultKvBootstrapIT nos dois módulos, incluindo ACL real e ausência de fallback. | Atendido no startup; atualização exige reinício. |
| Runtime sem proprietário | IAM runtime sem acesso ao KV proprietário/configuração Vault; grants explícitos; migração em processo separado. RuntimeDatabasePrivilegesIT (34 casos), VaultDatabaseIT e ACL iam-runtime. | Atendido no modo dinâmico; modo estático local ainda existe. |
| Renovação/rotação | VaultDatabaseIT: renovação além do lease original, teto do token, troca com transação fixada e recusa após expiração. | Atendido nos cenários medidos; sem SLA de carga. |
| Limpeza limitada | VaultDatabaseIT: revogação negada limita gerações e recupera após retorno da permissão. | Atendido; falhas persistentes podem adiar disponibilidade. |
| Autorização PAM | Escopos próprios, política vigente, MFA do sid original, prazo e propriedade do pedido. PrivilegedRequestServiceTest e cenários pamRequiresOwnScopeLivePolicyAndRealMfaThenRevokesWithAtomicAudit / pamAuthenticatedDenialsAreSanitizedAndAuditFailureRemainsClosed em BrowserAuthorizationIT. | Recusas e rollback comprovados. |
| Emissão única/reautorização | PamCredentialServiceTest e BrowserAuthorizationIT: concorrência, política alterada, resposta ambígua, auditoria indisponível e ausência de reentrega. | Atendido; resposta perdida não permite recuperar a senha. |
| Isolamento PAM | BrowserAuthorizationIT: diagnóstico SQL restrito, AppRole sem KV/runtime/Transit e revogação fora do prefixo negada. ACL iam-pam com mount próprio. | Atendido; conta de diagnóstico não é superusuário. |
| Expiração/revogação | BrowserAuthorizationIT e E2E: novas autenticações expiram; sessão aberta é terminada após recuperação do Vault. | Atendido com limite explícito: sessão existente pode sobreviver ao TTL durante queda do Vault. |
| Auditoria e recuperação | PamAuditTest, PamCleanupRecoveryIT e PropagationIT: fatos lógicos separados do reconhecimento Vault, rollback, retries persistidos, reinício e consulta central/cursor após falha do broker. | Atendido; prazo lógico não prova revogação física. |
| Exposição de segredos | Revisão de PamCredentialController (no-store, erros fixos), PamAudit (códigos permitidos), JdbcPrivilegedIssuances (metadados), objetos Vault com toString redigido e configuração de erros/logs. Arquivos locais ignorados e nenhum arquivo privado nos caminhos verificados do índice Git. | Controles locais revisados; não equivale a scan completo do histórico, de todos os logs ou de imagens. |

Fontes: [testes IAM](../../iam-core/src/test/java/com/securegate/iam/), [testes enforcer](../../policy-enforcer/src/test/java/com/securegate/enforcer/), [E2E](../../integration-tests/src/test/java/com/securegate/e2e/), [ACL runtime](../../infra/vault/iam-runtime.hcl), [ACL PAM](../../infra/vault/iam-pam.hcl), [ACL enforcer](../../infra/vault/policy-enforcer.hcl).

## Sequência de revisão e reprodução

1. Confirmar commit/branch e ausência de alterações funcionais posteriores às evidências.
2. Ler evidências [9A2c](../evidencias/etapa-09a2c.md), [9B2](../evidencias/etapa-09b2.md), [9C1](../evidencias/etapa-09c1.md), [9C2a](../evidencias/etapa-09c2a.md), [9C2b](../evidencias/etapa-09c2b.md), [9C3](../evidencias/etapa-09c3.md) e [9A3](../evidencias/etapa-09a3.md). Resultados antigos permanecem históricos, não são novas execuções.
3. Conferir a matriz contra código, ACL e nomes dos testes atuais. Separar controles de startup, runtime e operação administrativa.
4. Consolidar XML Surefire/Failsafe dos quatro módulos, sem duplicar suítes. Na revisão: 72 relatórios, 778 testes, zero falhas/erros/ignorados; os 11 E2E estão incluídos nesse total.
5. Verificar exclusões Git e arquivos rastreados, sem imprimir conteúdo de .env ou arquivos AppRole. Exclusão não prova inexistência no histórico.
6. Para reproduzir os testes após mudanças funcionais, executar `.\mvnw.cmd -B -ntp clean verify` com Java 21/Docker e registrar data, duração e resultado novos. Os testes usam serviços descartáveis; não aplicar provisionadores à stack persistente como parte do aceite automatizado.
7. Conferir links, documentação e git diff --check. Atualizar plano/guia com estado local e pendências remotas.

## Riscos residuais e responsáveis pela próxima entrega

| Limite/pendência | Tratamento e entrega responsável |
| --- | --- |
| CI/publicação/merge não consultados nesta revisão | Publicar branch/PR quando autorizado, acompanhar workflow CI e registrar resultado antes do aceite remoto. O workflow roda em PR, main ou disparo manual; push de feature isolado não garante execução. |
| Ausência de validação de imagens | Etapa 11A/11B: construir e analisar imagens/artefatos e verificar ausência de segredos. Não declarar atendida a parte do aceite que cita imagens. |
| Vault dev volátil, AppRole por arquivo e bootstrap compartilhado do broker | Operação local documentada; produção exige Vault persistente, TLS, entrega protegida e revisão de identidades na etapa 11. |
| Credenciais KV estáticas e snapshot em memória | Rotação coordenada por reinício, conforme runbooks KV. Não há refresh automático. |
| Sessão PAM aberta durante queda do Vault | Observar limpeza pendente, recuperar Vault e confirmar revogação efetiva; não prometer corte físico no TTL sob essa falha. |
| Emissão ambígua/órfã antes de persistir lease | Não reemitir o mesmo pedido; acompanhar prazo SQL/Vault e recuperação operacional. |
| Metas de latência, capacidade e filas sem ensaio global de carga | Etapa 10: massa, planos e medições; aprovação funcional não satisfaz metas de desempenho. |

## Decisão de avanço

**Aceite técnico local consolidado**, com os limites acima; o aceite global/remoto da etapa 9 permanece condicionado à publicação/CI e às verificações de entrega aplicáveis. A próxima implementação local é **10A — massa sintética e planos JMeter**, inicialmente com smoke reproduzível. Não declarar metas de desempenho, release ou prontidão de produção antes das respectivas medições/verificações.

Ver [evidências 9D](../evidencias/etapa-09d.md) e [guia de implementação](../GUIA-DE-IMPLEMENTACAO.md).
