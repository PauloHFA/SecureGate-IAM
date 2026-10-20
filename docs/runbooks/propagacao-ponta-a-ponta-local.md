# Propagação ponta a ponta — 7D1

## Executar

Na raiz, com JDK 21 e Docker disponíveis:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

O reactor empacota IAM/enforcer antes de executar `integration-tests`. O novo módulo inicia os dois JARs e quatro contêineres descartáveis; não exige Compose, `.env`, portas fixas, cadastro prévio ou segredos persistentes. As portas dos serviços de infraestrutura são alocadas pelo Testcontainers e a porta HTTP usa uma porta local livre. Como em qualquer reserva seguida de bind, uma ocupação concorrente da porta HTTP pode impedir o startup; o teste falha com prazo limitado.

O teste não deve ser executado por `test` isolado: precisa dos JARs da mesma versão produzidos na fase package. Os caminhos são relativos ao módulo Maven, tanto no Windows quanto no Linux.

## Evidências geradas

- `integration-tests/target/e2e/propagation.csv`: nove amostras, com alvo, número da amostra, eventId, correlationId, duração em ms, TTL Redis em ms e status HTTP final.
- `integration-tests/target/failsafe-reports/`: resultado dos três casos parametrizados, cada um com três medições.
- `integration-tests/target/e2e/iam-core.log` e `policy-enforcer.log`: diagnóstico local dos processos. Logs não são incluídos no artefato de medição da CI.

A CI preserva o CSV junto dos relatórios existentes. O CSV não contém JWT, refresh token, credenciais, e-mail ou identificador da identidade. Os arquivos AppRole temporários são removidos ao terminar; os serviços são encerrados antes dos contêineres.

## O que é medido

Token aceito com 200 → revogação/logout confirmado por HTTP → Outbox publicada → evento e efeito Redis → recurso com 401. Cada medição precisa ficar abaixo de 2 segundos. O teste mantém os polls padrão do publicador (1.000 ms) e consumidor (500 ms), sem acelerar artificialmente a propagação. Somente o observador faz polling de 25 ms.

São medidos jti sem refresh, família após rotação e sid no logout. Jti/família conservam a margem de 60 segundos do protocolo 7A; sid encerrado conserva a marca permanente, com TTL -1. A fixture usa políticas explícitas e rede/perfil de login de baixo risco administrados por HTTP; não simula MFA nem remove marcas. O evento é correlacionado até o Redis. A Outbox fica sem backlog antes de cada medição.

## Diagnóstico e limites

Falha no startup: consulte logs e disponibilidade de Docker/memória. Falha de prazo: preserve o CSV e os logs, verifique publicação e consumo antes de repetir; não altere o limite de 2 segundos para obter aprovação. Falha de assert antes da gravação de uma amostra pode deixar CSV parcial, e o Failsafe mantém a execução como falha.

O 401 pode ocorrer primeiro pela barreira local do IAM. Por isso o teste exige também a projeção Redis e publicação confirmada. O tempo representa um limite superior observado da convergência, sem atribuir a recusa exclusivamente ao Redis. Nove amostras em ambiente ocioso não demonstram latência de produção ou sob carga.

Os cenários de falha/replay/reordenação e bloqueios por limiar estão no [guia 7D2](resiliencia-ponta-a-ponta-local.md). Consulte [ADR-026](../adr/026-medicao-ponta-a-ponta.md) e [evidências](../evidencias/etapa-07d1.md).
