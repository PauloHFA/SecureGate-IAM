# ADR-037 — Renovação e troca dos pools PostgreSQL

- Data: 20/10/2026
- Estado: aceita para a entrega local 9A2c; substitui o ciclo de lease único da ADR-036

## Decisão

Manter um DataSource estável para Spring/JDBC e trocar gerações de conexões por trás dele. Cada geração possui seu próprio token AppRole, lease, credencial e Hikari. Renovar token e lease a partir da metade de seus TTLs; aplicar somente prazos confirmados e medir tempo com relógio monotônico. O limite utilizável continua dois segundos antes do menor prazo confirmado do token ou lease. TTL devolvido menor que dez segundos antecipa o prazo conhecido e inicia troca; dados inválidos não ampliam o prazo.

Um worker serial controla renovação, reautenticação e limpeza, com verificação a cada 250 ms. Falhas de renovação tentam uma nova geração por AppRole, lendo novamente os arquivos de bootstrap. Timeout/5xx não estendem o prazo anterior. Rejeição de credencial ou resposta inválida invalida a geração imediatamente; indisponibilidade temporária mantém o prazo confirmado anterior. Um watchdog independente verifica os limites a cada 100 ms e agenda fechamento de pools sem executar chamadas ao Vault. O empréstimo de conexão também verifica o prazo antes e depois da aquisição física.

Um candidato só é publicado após obter lease e validar uma conexão real com o grupo restrito. A publicação é atômica e não modifica a identidade do DataSource usada pelo transaction manager. Transações existentes conservam a conexão da geração original; conexões novas usam o candidato. O wrapper acompanha devolução por close/abort. A geração anterior pode drenar por até cinco segundos, sempre limitada pelo seu prazo. Depois disso, conexões não devolvidas são encerradas: não se promete preservar transações arbitrariamente longas.

Limitar a três gerações no total: com uma ativa válida, até duas ficam em retirada. Se a ativa também vencer, as três podem aguardar limpeza e nenhum novo candidato será aberto até liberar espaço. Cada pool permite até dez conexões (até trinta durante sobreposição). Fechamento físico usa no máximo três workers. Gerações pendentes não são removidas antes do término do fechamento físico. Não publicar um candidato depois do encerramento do DataSource. Quando não há geração válida, novas conexões falham; o worker continua tentando obter uma geração válida e o processo pode recuperar sem reinício.

## ACL e revogação

Conceder somente `update` em `sys/leases/renew/database/creds/iam-runtime/*`, com `allowed_parameters` limitado a `increment`. Usar lease ID no caminho e não enviar `lease_id` no corpo, evitando sobreposição que escaparia do prefixo da ACL. O runtime continua sem acesso ao KV proprietário, configurações, listagem ou renovação de outro papel. Renovação do próprio token usa a política padrão de renew-self.

Após fechar uma geração, revogar o token pai para revogar os leases filhos. Falha conserva a sessão para novas tentativas de revogação, com intervalo mínimo de um segundo, até o prazo conhecido do token. Não ampliar esse prazo sem confirmação. No limite, liberar o cliente HTTP e registrar abandono de limpeza; expiração/repetições restantes pertencem ao Vault e devem ser verificadas pelo operador. Uma resposta ambígua de renovação pode ter sido processada no servidor; o prazo local não é prova de que o token remoto já desapareceu.

A revogação SQL passa a verificar a existência do papel antes de desativar, terminar sessões e remover o login. Isso evita falha permanente quando o login já tiver sido removido administrativamente. O bootstrap/provisionamento do Database Engine deve reaplicar o JSON versionado; não é alteração das migrações Flyway.

## Observabilidade e limites

Métricas sem segredo/ID de lease/usuário: `iam.database.vault.renewals` (`kind=token|lease`, `outcome=success|failure`), `iam.database.vault.rotations` (`outcome=success|failure|deferred`), `iam.database.vault.available`, além dos contadores de leases e falhas de revogação. `iam.database.vault.cleanup.abandoned` exige investigação. Revogações persistentemente negadas podem ocupar os dois lugares de retirada e adiar novas trocas; prefere-se interromper acesso a crescer sem limite ou usar credencial vencida.

Não há SLA sob carga, renovação offline ou fallback para senha proprietária. Readiness pode falhar enquanto a geração estiver indisponível. A 9A2c trata tokens/leases do pool PostgreSQL; Transit preserva sua autenticação independente com nova sessão antes do cache expirar, e KV continua um snapshot de startup. Entrega protegida/rotação dos arquivos AppRole, produção com TLS, perda de estado do Vault dev, PAM e auditoria operacional completa continuam com seus requisitos próprios.

Fontes: [renovação de leases](https://developer.hashicorp.com/vault/api-docs/system/leases), [renovação de tokens](https://developer.hashicorp.com/vault/api-docs/auth/token) e [ACL com parâmetros](https://developer.hashicorp.com/vault/docs/concepts/policies).
