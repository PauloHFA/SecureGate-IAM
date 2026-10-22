# ADR-057 — Logs operacionais JSON com campos restritos

- Estado: implementada localmente na 10B3a; publicação/CI pendentes.
- Base: 771487a (10B2b2d); branch feat/structured-operational-logs.

## Contexto e divisão

A 10B3 combina mudanças distintas: serialização dos logs e ciclo de contexto síncrono/assíncrono. A 10B3a entrega um contrato JSON verificável sem alterar transportes ou o schema V22. A 10B3b tratará tracing HTTP; a 10B3c tratará contexto durável no outbox/RabbitMQ e consumo, incluindo novas migrações caso necessárias. Estas duas últimas são propostas, ainda sem implementação.

## Decisão

Usar StructuredLogFormatter<ILoggingEvent> e JsonWriter do Spring Boot 3.5.16 já adotado, com perfil opcional structured-logs nos dois executáveis. Configurar console e arquivo; desativar banner neste perfil. Sem novas dependências ou exportadores. Não usar ECS/Logstash padrão porque incluem mensagens, MDC e exceções arbitrárias.

Cada linha tem somente timestamp, level, service, event e correlationHash. service é constante por executável; event pertence a catálogo finito de templates conhecidos ou log.record. Nenhum argumento é serializado. Não exportar nome do logger/thread, mensagem formatada, throwable, stack trace, marker, pares SLF4J, MDC completo, payload, URL, SQL, headers ou identidade do usuário.

O correlationId HTTP já existente não muda: permanece no header/resposta e no contexto do filtro, com limpeza/restauração em finally. Exportar apenas SHA-256 do identificador limitado a 64 caracteres seguros; vazio quando ausente/inválido. Hash é pseudonimização determinística, não criptografia nem autorização; valores previsíveis podem ser enumerados. Não enviar credenciais nesse header. Não usar hash como label de métrica. Enforcer normalmente terá hash vazio até a entrega assíncrona.

Manter duas pequenas implementações de adaptador nos bootstraps preserva a biblioteca de eventos independente de Logback/Spring Boot. Os testes de contrato são equivalentes nos dois executáveis. Mudanças de schema exigem atualizar ambos.

## Consequências e limites

O perfil reduz diagnóstico: logs desconhecidos preservam somente nível/tempo/serviço e código genérico. Templates alterados passam a log.record até revisão explícita do catálogo. Não há captura de requisições bem-sucedidas, propagação W3C, spans, coletor de traces, Loki ou retenção centralizada nesta entrega. Eventos JSON são operacionais e não substituem auditoria persistida.

A garantia cobre os encoders gerenciados pelo Boot sob este perfil. stdout/stderr diretos, processos externos, inicialização anterior à configuração, erros internos do Logback, appenders/customizações externos e logs antigos ficam fora do contrato. Perfil desligado preserva logging anterior. Não prometer sanitização global por regex. Ativação por operador implica aceitar a menor granularidade de diagnóstico.

## Verificação e referência

Testar parsing, linha única, catálogo, hash conhecido, limites e ausência de segredos sintéticos nas superfícies descartadas. Inicializar contexto Boot mínimo sem autoconfiguração de infraestrutura e verificar encoders reais de console/arquivo em ambos os módulos; executar regressões de arquitetura e build completo. [Operação](../runbooks/logs-estruturados-local.md), [evidências](../evidencias/etapa-10b3a.md).

Contrato consultado: [StructuredLogFormatter 3.5.16](https://docs.spring.io/spring-boot/3.5/api/java/org/springframework/boot/logging/structured/StructuredLogFormatter.html) e [logging Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html). Conferido também no sources.jar da versão utilizada. Firecrawl recusou consulta sem chave nesta rede; documentação oficial utilizada como alternativa.
