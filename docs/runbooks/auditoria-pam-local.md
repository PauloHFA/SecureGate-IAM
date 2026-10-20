# Auditoria operacional PAM — 9C1

Consultar [contrato e decisões](../adr/040-auditoria-operacional-pam.md), [evidências](../evidencias/etapa-09c1.md) e [emissão PAM](credenciais-pam-local.md). A 9C1 registra fatos; recuperação/observabilidade 9C2 e o aceite integrado 9C3 continuam pendentes.

## Atualizar o ambiente

Parar os processos PAM, aplicar a V20 e reaplicar o perfil runtime antes de iniciar a versão nova. Não manter workers antigos durante a validação do novo contrato.

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Migrate-Database.ps1
.\infra\scripts\Start-IamCore.ps1 -EnablePamCredentials -EnableBootstrap
```

O migrador reaplica as concessões: o runtime pode gravar os marcadores de expiração, mas não alterar a correlação original depois do INSERT. O emissor/Vault do PAM continua com o provisionamento da 9B2; esta entrega não cria outro AppRole nem muda seu TTL. Para usar apenas pedidos, `-EnablePam` também ativa a observação de suas expirações. Não há flag adicional para a 9C1.

## Reproduzir o fluxo

1. Configurar usuário, cliente confidencial, políticas pam:request/pam:issue e MFA conforme a 9B2.
2. Enviar X-Correlation-ID legível e válido ao criar o pedido, por exemplo pam-request-demo. Cabeçalhos inválidos são substituídos pelo filtro existente.
3. Usar outra correlação ao emitir a credencial, por exemplo pam-issue-demo. Manter a resposta com senha apenas em memória, conforme o guia de emissão.
4. Consultar metadados e aguardar o prazo ou revogar o pedido. Expiração não renova validade nem reentrega segredo.
5. Conferir os fatos pela auditoria: o pedido expirado conserva a correlação da criação; os fatos de expiração/revogação da credencial conservam a correlação da emissão.
6. Repetir observação/reinício sem produzir duas transições de expiração para o mesmo objeto.

Usar os comandos de [consulta por cursor](consulta-auditoria-local.md) para a área particionada, com os serviços opcionais de publicação/consumo/ingestão configurados conforme seus guias. Gravar na auditoria produtora/Outbox não significa que o consumidor já confirmou a ingestão central; a 9C3 validará especificamente essa jornada PAM completa. Identificar o pedido por requestId e tratar correlação como referência, não como prova de identidade ou chave única.

## Ler os fatos corretamente

| Fato observado | Interpretação operacional |
| --- | --- |
| request.authorized | Pedido persistido com autorização; ainda não é uma credencial. |
| credential.issued / ISSUANCE_COMMITTED | IAM aprovou e registrou o lease; a resposta pode ter sido perdida. |
| request.expired ou credential.expired / LOGICAL_DEADLINE | O prazo lógico foi observado; não prova encerramento de sessão PostgreSQL. |
| request.revoked ou resposta administrativa 202 | Pedido revogado logicamente; verificar limpeza da credencial separadamente. |
| credential.revoked / VAULT_ACKNOWLEDGED | Vault confirmou a revogação solicitada pelo worker. |
| credential.failed | Ver o código fixo e o estado dos metadados; não tentar recuperar senha por GET ou reemitir no mesmo pedido. |

Os nomes completos têm prefixo pam. Não converter ausência temporária de evento em conclusão de que o objeto ainda é válido: a API aplica expiresAt diretamente, e a coleta pode estar pendente.

## Recusas e falhas

Uma identidade verificada recusada por política, escopo, MFA, rate limit ou consumo/confirmação de emissão produz pam.access.denied. O evento traz somente operação, motivo e estágio de servidor; não copia mensagens de erro, token, justificativa ou IDs de pedidos alheios. Repetir a tentativa pode gerar outro fato legítimo.

Token inválido/ausente não é usado para atribuir um ator. Erros de corpo e indisponibilidade operacional não são classificados como recusa de política por esta entrega. Se a auditoria da recusa falhar, o acesso não é liberado e a resposta sinaliza indisponibilidade.

Se o gravador de expiração falhar, marcador/evento são desfeitos e o próximo ciclo tenta novamente. Observar `iam.pam.expiration.audit.failures` para pedidos e `iam.pam.cleanup.failures` para o ciclo das credenciais, pelas métricas administrativas. Investigar banco, concessões V20 e dependências sem imprimir dados sensíveis. A 9C2 detalhará métricas, filas e política de recuperação.

Durante pausa do Vault, o evento de expiração pode estar confirmado enquanto a sessão PostgreSQL continua ativa. Na retomada, o worker tenta a revogação e registra o reconhecimento do Vault. Esse limite permanece conforme a 9B2; os novos fatos não o eliminam.

Linhas anteriores à V20 sem origem usam o UUID do pedido como correlação estável. Esse fallback não recupera a correlação HTTP antiga. Não alterar marcadores/origens manualmente para fazer um relatório aparentar sucesso.

## Validação

`clean verify` usa containers descartáveis. Os cenários focados cobrem rollback de auditoria, repetição/concorrência dos observadores, correlação durável, recusa autenticada sanitizada e expiração separada de revogação durante pausa/retomada. Os resultados efetivamente executados ficam nas evidências; este guia não declara SLA de observação, retenção ou recuperação de perda do Vault dev.
