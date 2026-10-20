# Resiliência ponta a ponta — 7D2

## Executar

Na raiz, com JDK 21 e Docker disponíveis:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

A suíte de `integration-tests` inclui a [medição saudável 7D1](propagacao-ponta-a-ponta-local.md) e cinco testes novos de falha/replay/bloqueio. O teste inicia e encerra sua infraestrutura; não exige Compose ou `.env`.

## O que esperar

| Cenário | Evidência exigida |
| --- | --- |
| Enforcer parado/reiniciado | Evento publicado retido na fila, projeção ausente durante parada, projeção/401 após reinício |
| Cópias exatas de revogação | Mesmo deadline/fingerprint após processar as cópias; nenhuma rejeição à DLQ |
| RabbitMQ stop_app/start_app | Mesmo fato pendente com erro persistido, depois publicado e projetado sem repetir revogação |
| Redis pausado/despausado | Recurso 503, entrega preservada e recuperação para projeção/401 |
| Política nova seguida de cópias antigas | Watermark atual conservado e recurso continua 200 após ALLOW novo |
| Falhas de login e cópias | Uma falha reentregue continua contando uma vez; limites de conta/IP efetivos no HTTP |
| Expiração do bloqueio | Recuperação natural do recurso e do mesmo refresh recusado, sem apagar chaves |

O cenário final preserva os limiares de cinco tentativas por conta e vinte por IP, mas reinicia o enforcer de teste com duração de bloqueio de três segundos. A configuração padrão de produção/local permanece em 300 segundos. O fluxo mede o IP da conexão, sem aceitar headers de encaminhamento.

## Artefatos e diagnóstico

- `integration-tests/target/e2e/resilience.csv`: uma linha PASS por cenário concluído; cenário que falha não recebe PASS.
- `integration-tests/target/e2e/propagation.csv`: as nove medições saudáveis, separadas das interrupções.
- `integration-tests/target/failsafe-reports/`: resultado dos oito casos, incluindo os três parâmetros de medição.
- `integration-tests/target/e2e/*.log`: logs locais dos processos, preservados entre reinícios. Não fazem parte do artefato de medição da CI.

A CI guarda os dois CSVs e relatórios. Um CSV parcial não representa aprovação: confira o Failsafe e a quantidade esperada de linhas (cinco de resiliência e nove de medição).

Em falha de recuperação, preserve os relatórios e logs. Verifique o fato na Outbox, publicação, mensagem pendente, marca do evento e efeito Redis. Os waits são limitados e não autorizam fallback permissivo. Os testes recuperam as interrupções controladas em `finally`; não execute os comandos de falha manualmente sobre a stack persistente.

A fila de auditoria descartável serve apenas para capturar mensagens originais de replay. Isso não implementa nem valida o consumidor de auditoria da etapa 8. Leia os limites e o critério de processamento das cópias na [ADR-027](../adr/027-resiliencia-ponta-a-ponta.md) e veja as [evidências](../evidencias/etapa-07d2.md).
