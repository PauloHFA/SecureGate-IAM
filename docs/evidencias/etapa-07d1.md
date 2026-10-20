# Evidências — 7D1: baseline e medição ponta a ponta

- Data: 2026-10-19.
- Branch: `feat/end-to-end-propagation`, base `15dfbf8` (7C2 publicada).
- Escopo: JARs reais, produção/publicação/consumo de fatos e convergência Redis/HTTP de revogação/logout.
- Entrega local; publicação e CI remota pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **530 testes, zero falhas, zero erros e zero ignorados**. Build concluído em 4 minutos e 1 segundo, às 20:45:18 de 2026-10-19 (UTC−03). Spotless, regras de arquitetura e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços.

Build em cópia isolada, com 298 arquivos relevantes de código/configuração/scripts idênticos por SHA-256. JARs preservados nos diretórios target dos serviços. Log: `.firecrawl/etapa7d1-verify.log`; relatórios, logs locais dos processos e CSV: `.firecrawl/etapa7d1-reports/`. O CSV também está em `integration-tests/target/e2e/propagation.csv`. Links locais, exemplos PowerShell e `git diff --check` conferidos; nenhuma correspondência entre credenciais locais e arquivos alterados. Artefatos de validação são ignorados pelo Git.

## Cenários e rastreabilidade

Três testes parametrizados executam três amostras cada, totalizando nove medições. O novo módulo não importa classes internas dos serviços, não substitui beans nem escreve grants/projeções diretamente. O cliente/usuário/políticas, rede/perfil de login, PKCE, consentimento, tokens, refresh, revogação e logout passam por HTTP. PostgreSQL, RabbitMQ, Redis e Vault são descartáveis.

- Access token sem refresh: jti aceito antes, evento `token.revoked` e marcador jti com prazo do evento acrescido da margem de 60 segundos.
- Família: rotação real de refresh e token renovado aceito; revogação da família projeta `authorizationId` e nega o token renovado.
- Logout: encerramento real da sessão emite `session.terminated`; sid recebe valor 0 e TTL -1, sem inventar limite para grants antigos.
- Cada operação precisa ter um único fato correlacionado, publicação confirmada, marcador do eventId e efeito Redis, além de rejeição HTTP 401.
- O token retorna 200 imediatamente antes da operação. A marca terminal deve estar ausente e a Outbox deve estar sem pendências antes do início da medição.

O CSV contém alvo/amostra, eventId/correlationId, duração monotônica observada, TTL da projeção e status HTTP. Não contém JWT, refresh token, senha, e-mail ou identificador de identidade. Os logs locais dos processos não entram no artefato de medição da CI.

## Resultados locais

| Alvo | Amostras | Mínimo (ms) | Máximo (ms) |
| --- | --- | --- | --- |
| ACCESS_TOKEN | 3 | 878,173 | 1088,377 |
| AUTHORIZATION | 3 | 986,233 | 1438,478 |
| SESSION | 3 | 1332,076 | 1370,385 |

Todas as nove amostras ficaram abaixo de 2.000 ms. A execução focal anterior também passou com nove amostras; a tabela corresponde exclusivamente ao build completo final.

O início é a confirmação HTTP recebida; o fim exige simultaneamente publicação confirmada, efeito/eventId no Redis e rejeição HTTP. Publicador/consumidor usam os defaults de 1.000/500 ms; somente o observador faz polling de 25 ms. As leituras e o HTTP introduzem sobrecusto, tornando a duração um limite superior amostrado.

## Aceite e limites

A 7D1 cobre o baseline de revogação/logout. A **7D2** ainda precisa integrar falhas temporárias, duplicação, reordenação e bloqueios por limiar. Os testes separados dos componentes anteriores continuam no build, sem serem apresentados como prova desses novos cenários ponta a ponta.

O IAM também pode rejeitar o token pela barreira local. Exigir projeção Redis e publicação evita usar somente essa recusa como evidência de propagação; não comprova que o 401 foi causado exclusivamente pelo Redis. As 7C1/7C2 já validam separadamente a aplicação da leitura distribuída ao HTTP.

Nove amostras sequenciais, sem backlog, não são benchmark nem garantia de percentil/latência de produção, carga ou multi-instância. Reconstrução Redis após perda/eviction, sessões compartilhadas e outros limites operacionais permanecem fora desta entrega. Nenhuma stack persistente ou credencial local foi alterada.

Referências: [ADR-026](../adr/026-medicao-ponta-a-ponta.md), [guia](../runbooks/propagacao-ponta-a-ponta-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).