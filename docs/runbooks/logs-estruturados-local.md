# Logs operacionais estruturados — 10B3a

## Preparação e ativação

Compilar com JDK 21 e Wrapper. O perfil structured-logs existe no iam-core e policy-enforcer. Usar os mesmos parâmetros/arquivos privados de inicialização documentados para cada serviço. Acrescentar o perfil aos já necessários, sem colocar credenciais nos argumentos:

```powershell
# Exemplos de argumento adicional ao comando java -jar habitual:
# --spring.profiles.active=structured-logs
# --spring.profiles.active=perfil-existente,structured-logs
# Arquivo opcional, em diretório privado:
# --logging.file.name=infra/.local/iam-operations.jsonl
```

Sem perfil, a saída anterior é preservada. O perfil não habilita consumers, listeners, endpoints ou coleta. Não executar um segundo processo sobre a stack persistente apenas para conferir logs. Um arquivo explícito usa a política de rotação do Boot/Logback: revisar tamanho/retenção/permissões conforme a operação; console depende da retenção do executor. Remover structured-logs da lista e reiniciar para reverter. Não muda banco, RabbitMQ, Vault ou Redis.

## Contrato de saída

| Campo | Conteúdo permitido |
| --- | --- |
| timestamp | Instante UTC do evento de logging. |
| level | Nível Logback. |
| service | iam-core ou policy-enforcer, constante. |
| event | Código do catálogo abaixo ou log.record. |
| correlationHash | SHA-256 hexadecimal de correlationId seguro, até 64 caracteres; vazio se ausente/inválido. |

Catálogo IAM: request.failure, audit.consumer.unavailable, login.history.unavailable, outbox.publisher.unavailable, outbox.maintenance.unavailable, audit.partitions.unavailable, policy.issuance.unavailable e policy.evaluation.unavailable. Enforcer: security.consumer.unavailable. Os códigos dependem de template exato; parâmetros e posição da exceção não são exportados. Outros logs, inclusive de bibliotecas e startup, usam log.record. Não derivar métricas de detalhes livres; o catálogo tem 9 valores possíveis no IAM e 2 no enforcer, contando o genérico.

Desde a 10B3b1, o IAM acrescenta traceId/spanId validados (sete campos); o enforcer mantém os cinco campos acima. Fora de contexto, os IDs ficam vazios. Ver [tracing HTTP](tracing-http-local.md).

Um registro JSON é uma linha terminada por LF. Mensagem, argumento, exceção, stack trace, nome de logger/thread, marker, pares SLF4J e MDC livre ficam fora do schema. Isto sacrifica diagnóstico detalhado; usar métricas e códigos para localizar o componente. Não é auditoria persistida nem access log.

## Correlação e limites

O filtro HTTP mantém X-Correlation-ID como antes. Para procurar nos logs, calcular SHA-256 UTF-8 do identificador de resposta e comparar correlationHash. O hash é determinístico, de alta cardinalidade e não serve como label Prometheus. Não é segredo ou prova de identidade; entradas previsíveis podem ser enumeradas. Nunca enviar senha/token/PII no header. Sem correlação no MDC, o valor é vazio; não se inventa um trace. Enforcer não recebe o contexto HTTP nesta entrega.

Apenas logging pelos encoders configurados é coberto. System.out/err, outros appenders/configurações, mensagens internas de falha de logging, fases anteriores à inicialização e processos externos exigem tratamento próprio. Logs anteriores não são reescritos. Perfil desativado não recebe essa proteção. Não afirmar que toda a aplicação está livre de segredos nos logs.

## Validação

```powershell
.\mvnw.cmd -B -ntp -pl iam-core,policy-enforcer -am test '-Dtest=SafeOperationalLogFormatterTest,StructuredLoggingProfileTest,CorrelationIdFilterTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djacoco.skip=true'
.\mvnw.cmd -B -ntp verify
```

O teste de perfil inicia contexto Boot mínimo sem autoconfiguração de banco/broker, ativa encoders reais de console/arquivo e remove seu arquivo temporário. Não é um smoke de IAM/enforcer completos. Os testes injetam somente marcadores sintéticos em mensagem, argumentos, exceção, MDC e pares SLF4J. Contratos testam CR/LF, tamanho e hash conhecido. verify inclui suites normais e gates de cobertura/arquitetura; smokes opcionais de observabilidade não são incluídos pelo nome.

Próximas entregas propostas: 10B3b2 clientes HTTP JDK/Vault; 10B3c contexto persistido no outbox/AMQP, restauração/limpeza no consumidor e validação fim a fim. Coleta/retensão centralizada de logs e backend de traces exigem decisão própria. [ADR-057](../adr/057-logs-operacionais-json-restritos.md), [evidências](../evidencias/etapa-10b3a.md).
