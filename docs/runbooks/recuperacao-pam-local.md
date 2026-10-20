# Recuperação PAM — 9C2a/9C2b

Pré-requisitos: [emissão PAM](credenciais-pam-local.md), [auditoria PAM](auditoria-pam-local.md), [ADR-041](../adr/041-recuperacao-da-limpeza-pam.md) e [ADR-042](../adr/042-recuperacao-do-observador-pam.md). A 9C2a cobre credenciais; a 9C2b cobre o observador de pedidos e o exercício de reinício da JVM.

## Atualização

1. Parar processos PAM antigos, preservando o banco e o Vault. Não reiniciar o Vault dev para atualizar o IAM.
2. Executar a validação local e aplicar V21/V22 com o migrador separado, que reaplica as concessões runtime.
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

## Observador de pedidos — 9C2b

Com `-EnablePam`, o observador funciona mesmo sem emissão de credenciais. Não há flag nova. Aplicar V22 e reaplicar o perfil runtime com processos antigos parados; workers antigos ignoram o próximo instante persistido. São concedidos UPDATE somente em observation_attempts, next_observation_at, last_observation_failure_at e last_observation_failure_code.

Cada pedido vencido é observado em uma transação curta de marcador/fato/Outbox. Em falha, o marcador volta ao estado anterior, o agendamento é salvo separadamente e os demais pedidos seguem. Os intervalos são 2–60 s com contador saturado em 30; nenhum item é descartado por atingir esse contador. O código persistido é OBSERVATION_FAILED, sem mensagem de exceção.

| Métrica | Significado |
| --- | --- |
| iam.pam.expiration.pending | Pedidos vencidos, não revogados, ainda sem fato de expiração confirmado. |
| iam.pam.expiration.ready | Pendentes elegíveis agora. |
| iam.pam.expiration.deferred | Pendentes aguardando retry persistido. |
| iam.pam.expiration.retrying | Pendentes com falha de observação registrada. |
| iam.pam.expiration.oldest.overdue.seconds | Tempo desde o vencimento do pedido pendente mais antigo. |
| iam.pam.expiration.snapshot.available | 1 após coleta válida; 0 em consulta indisponível/antes da primeira amostra. |
| iam.pam.expiration.snapshot.last.success | Instante Unix do último sucesso de coleta. |
| iam.pam.expiration.snapshot.failures | Falhas da consulta agregada. |
| iam.pam.expiration.audit.failures | Ciclos com ao menos uma falha no processamento, inclusive falha de banco/agendamento. |

Usar o mesmo acesso administrativo de métricas acima. A coleta acontece a cada cinco segundos no executor do observador, sem I/O no scrape. Verificar disponibilidade e idade do último sucesso antes de interpretar um valor; NaN não é fila vazia. Não somar snapshots de réplicas de uma mesma fila. O prazo aplicado pela API independe do atraso dessa auditoria.

Para recuperar, corrigir auditoria/banco/concessões, preservar os metadados e aguardar o próximo ciclo elegível. Ao reiniciar somente o IAM, manter PostgreSQL/Vault e iniciar a versão nova com as mesmas flags. Conferir prazo/correlação preservados e um fato pam.request.expired com Outbox. Não alterar expiresAt, marcadores ou contadores para aparentar conclusão. Pedido revogado não é candidato à observação de expiração.

## Exercício automatizado de reinício real

Executar o build completo na raiz. `PropagationIT#pamRequestObserverRecoversAfterAuditFailureAndRealJvmRestart` usa o JAR real e infraestrutura descartável; a fixture SQL cria um pedido vencido e uma restrição temporária de INSERT para simular indisponibilidade da auditoria. Após confirmar retry e rollback do marcador, encerra a JVM, remove a falha e inicia uma JVM com PID diferente no mesmo banco/Vault. Confere agenda, prazo e correlação preservados, um fato e uma Outbox. A restrição é removida em finally; não reproduzir a injeção de falha na stack persistente.

Esse teste não cria pedido pela API nem emite senha: a emissão fica desabilitada. A retomada do observador foi comprovada; reinício durante uso/entrega/revogação de uma credencial real e ingestão/consulta central integram a 9C3. Consultar as [evidências 9C2b](../evidencias/etapa-09c2b.md) para os comandos e resultados observados.

A [9C3](jornada-pam-integrada-local.md) complementa o cenário com pedido/emissão por HTTP, uso de credencial PostgreSQL, reinício com revogação conhecida pendente, falha/retomada de broker e consulta central. Não comprova recuperação de senha cuja resposta foi perdida nem perda de estado do Vault dev.

## Resultado incerto ou perda do Vault dev

1. Interromper novas emissões enquanto investiga; não reutilizar o pedido consumido nem tentar recuperar a senha por GET.
2. Preservar metadados, correlações, fatos de auditoria e registro do incidente. Não imprimir lease/token/senha em relatório compartilhado.
3. Com uma identidade administrativa PostgreSQL separada, verificar papéis de diagnóstico e sessões. Não conceder esses privilégios ao runtime/PAM para automatizar o contorno.
4. Em perda do Vault dev, a recriação do mount/AppRole não recupera o registro antigo dos leases. Reprovisionar somente após entender os acessos remanescentes e seguir o [guia de banco Vault](vault-database-renovacao-local.md).
5. Se necessária, fazer contenção/remoção administrativa dos papéis e sessões identificados, registrando as evidências no incidente. Não alterar marcadores para inventar VAULT_ACKNOWLEDGED.

Esta entrega não automatiza descoberta/remoção de órfãos ou reconciliação após perda de Vault. VALID UNTIL limita novos logins por senha; sessões já autenticadas dependem de encerramento efetivo. Não há retenção automática de metadados nesta divisão.

## Validação reproduzível

Os testes descartáveis da [9C2a](../evidencias/etapa-09c2a.md) simulam dez leases problemáticos seguidos de itens saudáveis, recriam serviço/repositório com a agenda persistida, disputam uma atualização de retry e provocam falha da auditoria após o RPC. A recuperação observada nesse ambiente não comprova capacidade de produção nem recuperação de perda de estado do Vault.
