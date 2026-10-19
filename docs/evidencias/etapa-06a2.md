# Evidências — 6A2: produtores de eventos de segurança

- Data: 2026-10-19.
- Branch: `feat/security-event-producers`, base `2e34a88` (6A1).
- Escopo: dados validados e produtores reais dos seis eventos de integração.
- Commit/publicação/CI remota/revisão: pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15 e Vault 1.21.4 temporários: **433 testes, zero falhas, zero erros e zero ignorados**. Duração do build final: aproximadamente 1 minuto e 49 segundos. Spotless, regras de arquitetura e cobertura JaCoCo mínima de 80% por pacote de domínio/aplicação do `iam-core` aprovados.

Build executado em cópia isolada para evitar interferência da IDE em `target`. Os 239 arquivos relevantes de código/configuração/scripts, incluindo JSON Schema, coincidem por SHA-256 com as fontes validadas. JAR preservado em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa6a2-verify.log`; relatórios em `.firecrawl/etapa6a2-reports/`, ignorados pelo Git.

A entrega acrescenta oito casos de validação dos dados no módulo compartilhado, sete casos HTTP de navegador e um caso em cada suíte administrativa de usuário/política. A suíte de navegador passou de 70 para 77 casos. Expectativas de atualização Flyway foram ajustadas para V10, preservando os testes de dados legados/ausência de backfill.

## Casos cobertos

- Seis tipos com campos obrigatórios, UUIDs, versões, motivos/alvos e expiração quando aplicável. Campos extras de credencial, endereço não numérico e identificações parciais são recusados. Schemas e referência URN conferidos, sem executar um motor completo de JSON Schema.
- Login recusado de conta conhecida gera tentativa com o mesmo attemptId do histórico, sem e-mail/senha no payload. Conta desconhecida gera evento sem userId, com motivo genérico e conexão observada.
- Falha de persistência do evento de login recusado desfaz contador/evento, mantém resposta genérica e histórico incompleto.
- Login suspeito real contém score/motivos e sid; GETs posteriores não duplicam a avaliação. Reautenticação com rede/dispositivo confiáveis e score baixo não gera suspeita nova e encerra o sid anterior.
- Falha no evento de login suspeito responde 503, desfaz contexto e não deixa navegador autenticado.
- Logout gera evento terminal com sid/usuário. Falha do evento desfaz contexto e fechamento da prova MFA, retorna erro e permite nova tentativa; recuperação confirma um único evento.
- Criação/edição de política preserva auditoria legada e produz policy.changed com versão/alvo/ação. Falha do novo evento desfaz a edição e seus eventos.
- Transição para LOCKED produz user.locked com nova versão, sem duplicar no-op. Falha da Outbox desfaz a mudança de estado.
- Access tokens de usuário mantêm authorization_id entre emissão/refresh. Revogação distingue autorização/família de access token individual, com prazo e jti quando aplicável, sem publicar token bruto.
- Reuso de refresh produz REFRESH_REUSE e invalida a família. Falha no evento desfaz a invalidação; repetição após recuperação confirma o efeito/evento uma única vez.
- Regressão de Client Credentials, OAuth/OIDC, MFA, políticas/risco, recursos, auditoria e migrações preservada.

Durante a validação foram corrigidos o leitor de Instant usado nos testes, a contagem de auditorias legadas após adicionar fatos de integração, uma colisão de rede entre fixtures e um método padrão desnecessário na porta de histórico que afetava a cobertura. O build final passou integralmente, sem reduzir o limite de cobertura.

Links locais, exemplos PowerShell e `git diff --check` conferidos. A comparação com credenciais locais não encontrou correspondências nos arquivos alterados.

## Aceite e limites

A **6A está funcionalmente implementada localmente**: envelope/Outbox na 6A1 e seis produtores na 6A2. A etapa 6 continua aberta: ainda não há publicação RabbitMQ, confirmação/retry, consumo Redis, retenção operacional ou medição de propagação.

Login suspeito usa score a partir de 70 e sinais reais, incluindo ausência conservadora; não significa fraude nem bloqueio automático. `user.locked` cobre estado administrativo, sem se confundir com limitação temporária de MFA. `session.terminated` cobre logout/reautenticação com fechamento efetivo de contexto, sem prometer notificação de toda expiração passiva ou queda do processo.

O evento de sid terminal não recebe prazo fictício. O enforcer precisa preservar tombstone ou calcular retenção segura para os grants associados. Tokens antigos sem authorization_id não permitem afirmar revogação offline por família sem outra verificação confiável. Erro de persistência não promete evento durável para tentativas de login quando o banco está indisponível; a cobertura permanece conservadora.

Nenhum servidor persistente, credencial ou topologia local foi alterado. A V10 foi validada nos bancos temporários; Flyway aplicará as migrações pendentes ao iniciar o novo JAR no ambiente local. Próxima entrega: 6B, publicação dos envelopes persistidos.

Referências: [ADR-019](../adr/019-produtores-de-eventos-de-seguranca.md), [guia](../runbooks/eventos-seguranca-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).
