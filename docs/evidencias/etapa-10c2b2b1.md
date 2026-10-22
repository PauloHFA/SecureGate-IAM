# Etapa 10C2b2b1 — validação no recurso protegido

Data: 22/10/2026 (UTC−03). Branch `feat/tracing-backend`, base `059cc48`. Implementação local sem commit/push/CI remota.

## Método

O cenário `resource` reutiliza as três fases do runner e a massa humana sintética com política `account:self/read`. A cada fase, cada thread executa `pkce.journey.success` uma vez. O plano mantém o Bearer em memória e usa um cliente JMeter separado, sem cookies, para repetir `GET /api/resources/account`. A asserção confere HTTP 200, sujeito preparado e validade residual. As amostras PKCE permanecem no CSV para auditoria, mas são excluídas de `requests`, falhas, p50/p95/p99 e janelas; `setupSamples` registra sua quantidade. O teste usa IAM, PostgreSQL, Redis, RabbitMQ e Vault descartáveis.

## Validação local

Comando curto: `./mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=TokenBaselineSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' '-Dsecuregate.load.scenario=resource' verify`. **1 teste, 0 falhas, 0 erros, 0 skips; BUILD SUCCESS** às 13:23:03 UTC−03, em 1 min 51 s. As fases foram 8/10/8 s com uma, duas e uma threads. No patamar foram 510 GETs, zero falhas, 51 req/s e p95 47 ms; sete janelas completas. Houve duas preparações PKCE no patamar, fora dos percentis do GET. O teste também conferiu amostras IAM e de quatro dependências e ausência de credenciais/JWT nos artefatos de carga.

Seleção de 60 s: mesmo comando com `'-Dsecuregate.load.steady.seconds=60' '-Dsecuregate.load.window.seconds=10'`; em execução. XML do plano, sintaxe PowerShell e `git diff --check` passaram na verificação estática.

## Limites

O tempo medido é o GET protegido completo, incluindo rede, cliente JMeter, autorização e resposta; não isola custo de validação criptográfica/cache nem comprova a referência p95 < 20 ms. A taxa divide GETs pela duração configurada da fase, que inclui a preparação inicial; não há controle independente de taxa ofertada. Tokens sintéticos expiram em até 120 s, por isso cada fase é limitada a 90 s. O ensaio pequeno não demonstra capacidade, estabilidade prolongada nem saturações. O `verify` completo ainda não foi repetido após a 10C1. A 10C2b2b2 medirá revogação, e a 10C2b2b3 medirá auditoria. [Operação](../runbooks/medicao-validacao-recurso-local.md).
