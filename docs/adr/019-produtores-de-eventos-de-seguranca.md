# ADR-019 — Produtores dos seis eventos de segurança

- Data: 2026-10-19
- Estado: implementada localmente; publicação/CI remota/revisão pendentes
- Entrega: 6A2

## Decisão

Integrar as seis chaves previstas no plano aos fluxos reais. Preservar a auditoria legada e usar o mesmo adaptador transacional da 6A1 para os novos fatos. Não há publicador RabbitMQ nesta entrega.

`SecurityEventData` valida os dados das seis chaves no construtor do envelope v1: campos obrigatórios, lista de chaves permitidas, IDs UUID quando aplicáveis, versão não negativa, enumerações, horário de expiração e pares de identidade. Campos inesperados são recusados, inclusive uma chave de senha. Demais tipos de auditoria conservam seus formatos. O [schema dos dados de integração](../../libs/security-events/src/main/resources/contracts/security-integration-event-v1.schema.json) complementa o [envelope v1](../../libs/security-events/src/main/resources/contracts/security-event-v1.schema.json). As chaves eram reservadas na 6A1 e ainda não tinham produtores reais; o formato do envelope permanece v1.

## Produtores e dados

| Tipo | Ponto de produção | Dados relevantes |
| --- | --- | --- |
| `auth.login.failed` | Falha do formulário de login | `attemptId`, motivo genérico, endereço observado; usuário/versão somente quando a conta tentada existe |
| `auth.login.suspicious` | Primeira observação confirmada do sid após autenticar | usuário, versão, sid, score, motivos e endereço observado quando válido |
| `policy.changed` | Criação/edição de política confirmada | policyId, versão, operação, estado habilitado, alvo e ação da regra |
| `user.locked` | Transição administrativa real para LOCKED | userId, versão, estado LOCKED e motivo ADMINISTRATIVE |
| `token.revoked` | Revogação OAuth e detecção de reuso de refresh | autorização/cliente, alvo, motivo, prazo, jti quando aplicável e identidade/sid quando houver usuário |
| `session.terminated` | Fechamento do contexto no logout ou substituição por novo login | usuário, versão, sid e motivo BROWSER_CLOSED |

Os mapas continuam com valores textuais. `schemaVersion` versiona o envelope; `data.version` é a versão da entidade/identidade. A conta associada a `auth.login.failed` é a **conta tentada**, não uma identidade autenticada. E-mail digitado e senha não são publicados. Endereços são literais numéricos observados no servidor; não vêm de parâmetros ou forwarded headers. São dados operacionais que também exigem controle de acesso e retenção.

## Login e sessão

`JdbcLoginRiskHistory` grava contador conhecido e evento na mesma UnitOfWork. Tentativa de conta inexistente também gera fato, sem inventar userId. Falha de persistência desfaz o contador/evento e mantém a resposta genérica de autenticação; o histórico continua marcando sua cobertura como incompleta por cinco minutos. Não se promete evento durável quando o banco está indisponível, nem se transforma ausência de evento em prova de segurança.

A V10 acrescenta `login_assessed` aos contextos. Contextos existentes começam avaliados, sem eventos retroativos; o produtor novo insere falso e assume a avaliação numa atualização condicional dentro da transação. GETs posteriores podem atualizar a observação, mas não geram novas suspeitas para o mesmo sid. Falha na avaliação ou Outbox desfaz a criação/assunção, permitindo tentativa posterior.

A classificação de suspeita usa o `RiskCalculator` real, alvo `auth:login` e score **a partir de 70**. Esse limiar é específico do evento, não uma redefinição das faixas do motor. Sinais ausentes e aquecimento do histórico conservam os pesos conservadores; um primeiro login pode ser classificado como suspeito por falta de confiança. O evento informa isso nos motivos. Não afirma fraude nem bloqueia a conta automaticamente.

O handler de sucesso agrupa fechamento do sid/prova anterior e criação/observação do novo contexto na mesma UnitOfWork. Se a persistência falhar, remove a autenticação do navegador, invalida a sessão Servlet e responde 503 sanitizado. O cookie de dispositivo não é credencial de autenticação. Logout agrupa fechamento da prova e do contexto com seu evento; falha impede confirmar o encerramento e retorna erro, com os dados transacionais preservados para nova tentativa. A sessão Servlet não é um recurso transacional PostgreSQL; a validação de falha/repetição está registrada nos testes HTTP.

`session.terminated` é emitido uma vez por fechamento efetivo do contexto, inclusive na reautenticação. Fechar novamente uma linha já fechada não emite outro fato. Expiração passiva de sessão, queda do processo e toda chamada isolada a `MfaProofs.revokeSession` não são novos produtores nesta entrega. Não se cria prazo fictício para um sid terminal: o consumidor da etapa 7 deverá preservar o tombstone ou calcular uma retenção segura considerando todos os grants associados, inclusive códigos ainda resgatáveis e refresh. A auditoria não declara revogação distribuída já aplicada.

## Políticas e bloqueio

`policy.created`/`policy.updated` continuam existentes. Cada mudança também gera `policy.changed` com alvo e ação, permitindo invalidar cache e comparar versão sem enviar o documento completo. Falha do segundo evento desfaz política e primeiro evento.

`user.locked` cobre transição para o estado administrativo LOCKED, sem duplicar no-op. Não representa o bloqueio temporário de tentativas MFA, que continua nos eventos MFA próprios. O consumidor deve confrontar a versão com o estado atual e com eventos de identidade posteriores; não manter um bloqueio antigo após uma transição mais recente para ACTIVE. A Outbox já preserva `identity.user.statuschanged` com estado e versão para essa evolução. A estratégia de cache/ordenação continua na etapa 7.

## Revogação

Access tokens de usuário novos incluem `authorization_id`, estável entre emissão e refresh da mesma autorização. Não se adiciona essa claim aos ID tokens. A claim não substitui assinatura, issuer, audiência, jti ou sid. Client Credentials continua sem contrato de família de usuário.

O evento diferencia `ACCESS_TOKEN` (jti obrigatório) de `AUTHORIZATION` (família revogada), com motivo `CLIENT_REVOCATION` ou `REFRESH_REUSE`. O prazo de uma família é pelo menos a expiração absoluta da família mais cinco minutos, cobrindo os access tokens que possam ter sido emitidos no fim da janela; preserva também a expiração do access token atual se maior. Revogar um único token usa sua expiração original. Consumidores futuros podem ignorar efeitos já expirados, sem registrar entregas como concluídas antes de aplicar o efeito necessário.

O evento nasce sob os mesmos locks/transações que invalidam o token/família. Repetição de uma revogação já aplicada não gera outro fato. Valor desconhecido ou cliente estrangeiro não publica metadados. Falha na Outbox também desfaz invalidação; replay de refresh só confirma a revogação quando o evento foi persistido. Token bruto, refresh e hashes históricos não entram nos dados.

Tokens anteriores a esta entrega podem não ter `authorization_id`. Eles continuam válidos conforme as regras locais existentes. O enforcer não poderá declarar revogação offline por família para esses tokens sem outra consulta confiável ou sem aguardar sua expiração. A 6A2 não muda a barreira online do recurso local.

## Limites

Sem backfill, envio RabbitMQ, consumo Redis ou medição de propagação. UUID/eventId serve à idempotência de entrega; versões distinguem estado de política/identidade, e sid/jti/authorizationId delimitam fatos terminais. Não há ordenação global por timestamp nem exactly-once. Confirmação/retry serão implementados na 6B; retenção e recuperação, na 6C; efeitos distribuídos, na etapa 7.

Ver [guia](../runbooks/eventos-seguranca-local.md), [evidências](../evidencias/etapa-06a2.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).
