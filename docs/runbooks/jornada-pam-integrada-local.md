# Jornada PAM integrada — 9C3

Consultar [ADR-043](../adr/043-aceite-integrado-pam.md), [evidências](../evidencias/etapa-09c3.md) e [passo a passo](../GUIA-DE-IMPLEMENTACAO.md). Esta entrega valida os caminhos existentes em conjunto e corrige o bootstrap OAuth para permitir execução com migrações externas/Flyway desabilitado.

## Executar o aceite descartável

Na raiz, com Java 21 e Docker Linux disponíveis:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

O reactor empacota os JARs e executa `PropagationIT` com processos Java separados. Os cenários pamJourney* usam PostgreSQL, Redis, RabbitMQ e Vault descartáveis, com senhas geradas em memória e arquivos temporários. Não leem `.env`, não usam a stack persistente e não exigem segredos reais. Resultados ficam em `integration-tests/target/e2e/resilience.csv`; os logs dos processos ficam no mesmo diretório, ignorado pelo Git. Não copiar respostas de emissão, tokens ou arquivos temporários para relatórios.

Para desenvolvimento focado, empacotar primeiro e selecionar os dois cenários com as opções registradas nas evidências. A seleção focada não substitui o build completo de aceite.

## Matriz do aceite

| Ponto | Evidência observada |
| --- | --- |
| Autenticação | Cliente e usuário criados por API; código/PKCE e consentimento de pam:request/pam:issue. |
| MFA | Pedido recusado antes do cadastro; confirmação TOTP pela interface autenticada, usando semente real somente em memória. |
| Pedido/emissão | Justificativa/TTL por HTTP, uma resposta com senha, GET sem reentrega e repetição recusada. |
| Uso | Login PostgreSQL real lê a visão de diagnóstico e recebe 42501 ao tentar ler identidades. |
| Runtime | IAM inicia com membro do grupo restrito e Flyway desabilitado; runtime não lê auditoria central, ingestão não lê Outbox. |
| Vault indisponível | Pedido revogado via API administrativa; lease conhecido permanece REVOKING com retry e nenhum fato antecipado de revogação. |
| Reinício | Encerrar IAM, retomar Vault e iniciar outra JVM preservando banco/lease; papel removido, sessão encerrada e fato central consultável. |
| Broker indisponível | Pedido/credencial vencem com RabbitMQ parado; fatos permanecem na Outbox não publicada e ainda não aparecem na auditoria central. |
| Retomada/consulta | Após retomar RabbitMQ, os fatos chegam pelo consumidor/pool de ingestão e podem ser consultados por correlação/cursor. |
| Dados sensíveis | Auditoria central e Outbox não contêm senha, usuário SQL ou access token usados pelo teste. |

LOGICAL_DEADLINE continua significando prazo observado; VAULT_ACKNOWLEDGED continua significando reconhecimento do RPC. O teste verifica também papel/sessão PostgreSQL de forma independente. Não deduzir encerramento de sessão apenas de um evento ou de um TTL.

## Reproduzir a jornada na stack local preparada

Aplicar os pré-requisitos dos guias de [credenciais PAM](credenciais-pam-local.md), [banco Vault](vault-database-local.md), [identidades de auditoria](identidades-auditoria-local.md), [consumidor](consumidor-auditoria-local.md) e [consulta](consulta-auditoria-local.md). O banco deve estar migrado até V22, os perfis runtime/PAM/ingestão provisionados e os arquivos AppRole/senhas dedicadas presentes. A 9C3 não adiciona migração.

Com esses pré-requisitos já atendidos, o conjunto de flags existente é:

```powershell
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap -EnableVaultDatabase -EnablePamCredentials -EnableOutboxPublisher -EnableAuditConsumer -EnableAuditQuery
```

Esse comando é orientação para o ambiente local; não foi executado contra a stack persistente nesta entrega. O modo de banco dinâmico exige o provisionamento próprio da 9A2 e aplica migração separadamente. A correção OAuth evita exigir um bean flywayInitializer ausente; schema ou credencial incorretos continuam sendo erro, sem conceder DDL/fallback ao runtime.

1. Configurar usuário/cliente/políticas e MFA pelos guias PAM, mantendo tokens/senha somente em memória.
2. Usar correlações válidas distintas para criação, emissão e revogação administrativa.
3. Emitir e conectar na visão de diagnóstico. Não usar o login humano como runtime nem como operador de auditoria.
4. Consultar `/api/admin/audit/events` com bootstrap, janela UTC e correlationId, conforme o guia de consulta. A consulta lê event_log pelo pool dedicado; não é leitura da Outbox.
5. Confirmar fatos com requestId/eventId e as correlações de origem. Fixar janela/filtros ao percorrer cursores. Não tratar correlação como identidade/chave exclusiva.
6. Em indisponibilidade, acompanhar as métricas da [recuperação PAM](recuperacao-pam-local.md) e da [Outbox](operacao-outbox-local.md). Preservar metadados e aguardar recuperação; não alterar marcadores para aparentar sucesso.

As injeções de falha/reinícios automatizados pertencem aos containers descartáveis. Não reproduzir perda do Vault dev ou intervenção SQL administrativa na stack persistente como parte de um smoke test.

## Limites e próximo trabalho

Aceite local da 9C3 não encerra toda a etapa 9: ainda faltam KV do enforcer (9A3), hardening e aceite final global, além de publicação/CI/revisão remotas. O teste usa login runtime sintético com grupo restrito; renovação de credenciais dinâmicas do runtime permanece validada separadamente na 9A2c.

Pausa/retomada do Vault não prova recuperação de perda de seu estado. Sessões ativas podem ultrapassar TTL durante falha do Vault; a limpeza depende de retomada/revogação efetiva. Não há recuperação de senha perdida, reconciliação automática de órfãos, HA, SLA ou medição de produção nesta entrega.
