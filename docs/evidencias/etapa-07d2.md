# Evidências — 7D2: falhas, replay e bloqueios ponta a ponta

- Data: 2026-10-19.
- Branch: `feat/end-to-end-resilience`, base `70e7f4a` (7D1 local).
- Escopo: resiliência da cadeia real, cópias exatas, política fora de ordem e bloqueios por falhas reais de login.
- Entrega local; publicação e CI remota pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **535 testes, zero falhas, zero erros e zero ignorados**. Build concluído em 4 minutos e 44 segundos, às 21:12:53 de 2026-10-19 (UTC−03). Spotless, regras de arquitetura e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços.

Build em cópia isolada com 298 arquivos relevantes de código/configuração/scripts idênticos por SHA-256. JARs preservados nos targets dos serviços; log `.firecrawl/etapa7d2-verify.log`; relatórios e logs locais dos processos em `.firecrawl/etapa7d2-reports/`. Ambos os CSVs também estão em `integration-tests/target/e2e/`. Artefatos são ignorados pelo Git. Links locais, exemplos PowerShell e `git diff --check` conferidos; nenhuma correspondência com credenciais locais nos arquivos alterados.

## Cenários adicionais

Cinco testes novos usam os JARs reais do IAM/enforcer, PostgreSQL, RabbitMQ, Redis e Vault descartáveis:

1. Enforcer parado: fato confirmado por HTTP e publicado fica na fila sem efeito Redis; reinício recupera projeção/401. Três cópias exatas preservam deadline e fingerprint, sem DLQ. Logout real posterior na mesma fila confirma o processamento das cópias.
2. Broker parado: revogação transacional permanece com published_at nulo e erro de publicação persistido; broker reiniciado permite entrega do mesmo eventId e efeito, sem nova revogação HTTP.
3. Redis pausado: recurso válido recebe 503 sem detalhe interno; a revogação publicada permanece pendente durante a pausa, por mais que o timeout do consumidor. Despausa permite efeito/401 sem DLQ.
4. Política ALLOW → DENY → ALLOW por API: recurso responde 403 e volta a 200. Cópias de versões antigas entregues após a nova preservam watermark e acesso atuais. Evento real posterior na mesma fila confirma que o replay foi atravessado.
5. Falhas reais de login: primeira falha reentregue três vezes continua contando uma vez; cinco tentativas por conta bloqueiam recurso, refresh e login correto com erro genérico, sem sessão autenticada. Vinte por IP bloqueiam uniformemente conta conhecida/desconhecida e não geram novas falhas de senha quando recusadas antes das credenciais. Expiração natural recupera recurso e o mesmo refresh antes negado.

O replay captura corpo e propriedades originais na fila de auditoria de teste; não altera/fabrica fatos nem consome filas do enforcer. Todas as mutações de identidade, autorização, política e falha de login continuam passando pelo IAM real. As consultas SQL/Redis de observação são somente leitura, sem apagar marcas ou forçar timestamps.

Somente no cenário final, o enforcer de teste recebe duração de bloqueio de três segundos, preservando janela de 300 segundos e limiares padrão 5/20. Os padrões dos serviços e a stack persistente permanecem intactos.

## Resultados e artefatos

Os cinco cenários novos passaram tanto na execução focal quanto no build completo. A tabela abaixo corresponde às nove amostras saudáveis do build completo final, todas abaixo de 2.000 ms.

| Alvo saudável | Amostras | Mínimo (ms) | Máximo (ms) |
| --- | --- | --- | --- |
| ACCESS_TOKEN | 3 | 712,436 | 1308,248 |
| AUTHORIZATION | 3 | 1154,362 | 1244,896 |
| SESSION | 3 | 1104,966 | 1168,161 |

`resilience.csv` contém uma linha PASS por cenário concluído (cinco esperadas). A suíte Failsafe continua reprovada se um cenário falhar, ainda que o CSV parcial exista. A CI preserva ambos os CSVs junto dos relatórios; logs dos processos permanecem locais. Nenhum CSV contém JWT, refresh token, senha, e-mail ou identidade.

## Aceite e limites

O escopo funcional local da etapa 7 está validado com a medição saudável da 7D1 e os cenários integrados da 7D2. Publicação, CI remota e revisão/merge continuam pendentes. Próxima entrega: **8A**, modelagem e particionamento da auditoria com deduplicação global.

Recuperação de indisponibilidade usa espera limitada distinta do SLA saudável de dois segundos. Não se promete esse SLA durante uma interrupção deliberada. O IAM também tem barreiras locais: a projeção/publicação é conferida separadamente do 401/403; não se atribui a recusa exclusivamente ao Redis.

As evidências não demonstram todas as permutações de eventos, falha permanente/perda de dados, reconstrução Redis, HA/cluster, sessões compartilhadas ou desempenho sob carga. Reordenação ponta a ponta é exercitada com política; identidade/timestamps/versionamento conservam seus testes de componente existentes. Rate limiting permanece assíncrono, sem limite rígido de QPS.

Referências: [ADR-027](../adr/027-resiliencia-ponta-a-ponta.md), [guia](../runbooks/resiliencia-ponta-a-ponta-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).