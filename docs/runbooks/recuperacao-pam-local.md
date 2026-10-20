# Recuperação da limpeza PAM — 9C2a

Pré-requisitos: [emissão PAM](credenciais-pam-local.md), [auditoria PAM](auditoria-pam-local.md) e [ADR-041](../adr/041-recuperacao-da-limpeza-pam.md). Esta divisão cobre credenciais; a recuperação do observador de pedidos permanece na 9C2b.

## Atualização

1. Parar processos PAM antigos, preservando o banco e o Vault. Não reiniciar o Vault dev para atualizar o IAM.
2. Executar a validação local e aplicar V21 com o migrador separado, que reaplica as concessões runtime.
3. Iniciar a versão nova com a flag de emissão já existente. O agendamento e os gauges não exigem outra flag.

```powershell
.\mvnw.cmd -B -ntp clean verify
.\infra\scripts\Migrate-Database.ps1
.\infra\scripts\Start-IamCore.ps1 -EnablePamCredentials -EnableBootstrap
```

Não misturar workers antigos e novos: o antigo ignora o próximo instante de tentativa. As colunas novas são aditivas e registros existentes começam elegíveis. A concessão extra permite somente atualizar os quatro campos de recuperação; origem, conteúdo do pedido e permissões administrativas continuam protegidos.

## Ler a fila

Usar a consulta administrativa protegida de métricas conforme [operação da Outbox](operacao-outbox-local.md); não publicar o token bootstrap em comandos de histórico ou logs. Ler a configuração local sem imprimir o token:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$settings = Read-LocalEnvironment (Join-Path (Get-Location) '.env')
$headers = @{ Authorization = 'Bearer ' + $settings.IAM_BOOTSTRAP_TOKEN }
$metricsBase = 'http://127.0.0.1:8080/actuator/metrics'
Invoke-RestMethod -Uri "$metricsBase/iam.pam.cleanup.snapshot.available" -Headers $headers
Invoke-RestMethod -Uri "$metricsBase/iam.pam.cleanup.snapshot.last.success" -Headers $headers
Invoke-RestMethod -Uri "$metricsBase/iam.pam.cleanup.pending" -Headers $headers
```

Essa leitura exige bootstrap habilitado e seu token administrativo; sessão de usuário/JWT OAuth não concede acesso a essas métricas.

| Métrica | Significado |
| --- | --- |
| iam.pam.cleanup.pending | Claims com pelo menos 30 s, leases em revogação e leases emitidos vencidos/com pedido revogado. |
| iam.pam.cleanup.ready | Pendentes elegíveis agora. |
| iam.pam.cleanup.deferred | Pendentes aguardando o próximo instante persistido. |
| iam.pam.cleanup.retrying | Pendentes com ao menos uma falha de limpeza registrada. |
| iam.pam.cleanup.ambiguous | Emissões FAILED sem lease conhecido; contagem conservadora de resultados incertos. |
| iam.pam.cleanup.oldest.pending.age.seconds | Tempo desde a criação do claim pendente mais antigo; não mede exclusivamente atraso após o TTL. |
| iam.pam.cleanup.snapshot.available | 1 após coleta bem-sucedida; 0 antes da primeira coleta ou após falha da consulta. |
| iam.pam.cleanup.snapshot.last.success | Instante Unix do último sucesso; verificar também se ficou antigo. |
| iam.pam.cleanup.failures | Ciclos com pelo menos uma falha; inclui falha de auditoria. |
| iam.pam.cleanup.snapshot.failures | Consultas do agregado que falharam. |

A amostra é atualizada a cada cinco segundos, com atraso adicional se o mesmo executor estiver executando RPCs. Valores NaN indicam indisponibilidade, não zero. A soma ready + deferred equivale a pending em cada amostra. Em réplicas, não somar os gauges de uma mesma fila. A métrica de resultados incertos não possui limpeza automática por prazo.

## Falha temporária e reinício do IAM

1. Investigar disponibilidade do Vault, acesso AppRole, PostgreSQL, concessões V21 e auditoria sem registrar respostas sensíveis.
2. Confirmar crescimento de falhas e presença de deferred/retrying. Uma recusa de permissão persistente exige corrigir a causa; o worker não a converte em sucesso.
3. Restaurar a dependência. O intervalo cresce de 2 a 60 s; o ciclo adiciona atraso de execução. Não há SLA de liberação.
4. Se reiniciar somente o IAM, preservar banco e Vault. O próximo instante é lido do banco pela nova instância; reiniciar não reinicia o contador nem reemite senha.
5. Conferir redução de pendentes e o fato pam.credential.revoked com a correlação da emissão. Distinguir esse reconhecimento do prazo lógico e da confirmação administrativa de ausência de sessões.

Falha de auditoria pode obrigar a repetir a revogação já executada no backend. A transação impede declarar o estado terminal sem o fato correspondente. Falha do banco ao salvar retry não perde o registro original; pode causar tentativas mais frequentes até a recuperação.

## Resultado incerto ou perda do Vault dev

1. Interromper novas emissões enquanto investiga; não reutilizar o pedido consumido nem tentar recuperar a senha por GET.
2. Preservar metadados, correlações, fatos de auditoria e registro do incidente. Não imprimir lease/token/senha em relatório compartilhado.
3. Com uma identidade administrativa PostgreSQL separada, verificar papéis de diagnóstico e sessões. Não conceder esses privilégios ao runtime/PAM para automatizar o contorno.
4. Em perda do Vault dev, a recriação do mount/AppRole não recupera o registro antigo dos leases. Reprovisionar somente após entender os acessos remanescentes e seguir o [guia de banco Vault](vault-database-renovacao-local.md).
5. Se necessária, fazer contenção/remoção administrativa dos papéis e sessões identificados, registrando as evidências no incidente. Não alterar marcadores para inventar VAULT_ACKNOWLEDGED.

Esta entrega não automatiza descoberta/remoção de órfãos ou reconciliação após perda de Vault. VALID UNTIL limita novos logins por senha; sessões já autenticadas dependem de encerramento efetivo. Não há retenção automática de metadados nesta divisão.

## Validação reproduzível

Os testes descartáveis da [9C2a](../evidencias/etapa-09c2a.md) simulam dez leases problemáticos seguidos de itens saudáveis, recriam serviço/repositório com a agenda persistida, disputam uma atualização de retry e provocam falha da auditoria após o RPC. A recuperação observada nesse ambiente não comprova capacidade de produção nem recuperação de perda de estado do Vault.
