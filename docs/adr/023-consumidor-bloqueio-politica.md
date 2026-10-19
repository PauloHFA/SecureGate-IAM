# ADR-023 — Projeções de bloqueio e política

- Estado: aceita para implementação local na 7B.
- Data: 2026-10-19.
- Complementa o [consumidor de revogação](022-consumidor-de-revogacao.md).

## Decisão

O processo `policy-enforcer`, ativado pelo switch existente, passa a consumir também `enforcer.block-policy`, em worker/canal independente da revogação. O transporte da 7A é reutilizado com fila e prefixo de métricas limitados a essas duas filas. Mantém ACK depois do efeito, NACK/requeue para falha transitória e rejeição à DLQ para mensagem inválida/conflitante. Não há novo segredo, fila ou migração SQL.

A fila aceita `identity.user.created`, `identity.user.updated`, `identity.user.statuschanged`, `identity.user.passwordchanged`, `user.locked`, `policy.changed`, `auth.login.failed` e `auth.login.suspicious`. Tipos futuros do wildcard de identidade são recusados até existir contrato explícito. Evolução de identidade exige status ACTIVE/LOCKED/DISABLED e versão não negativa, sem dados pessoais extras; userId vem do resource UUID. A validação dos quatro tipos administrativos foi adicionada ao contrato compartilhado e ao schema de integração v1, preservando o formato já emitido pelo IAM.

## Estado versionado

Identidade conserva `versão|status`, e política conserva `versão|enabled|digest-do-alvo`, em chaves sem expiração. Versões são inteiros Java long não negativos, codificados como 19 dígitos e comparados como strings no Lua. Isso evita arredondamento acima de 2^53. Versão menor não altera estado; versão igual e conteúdo diferente é conflito; versão maior substitui. `user.locked` e o evento administrativo equivalente podem chegar na mesma versão sem conflito quando ambos dizem LOCKED.

O estado persistente impede que mensagem antiga restaure bloqueio ou política superada após a expiração de um cache. Desbloqueio depende de estado ACTIVE de versão maior. Política é uma marca de versão/invalidação, não um documento de regras nem uma decisão ALLOW: nenhum consumidor interpreta ausência ou enabled como autorização.

## Observações e prazos

Falhas de login alimentam sorted sets por conta/versão e por IP numérico normalizado, usando attemptId como membro e horário do evento como score. Conta desconhecida não recebe identidade fictícia; apenas IP observado é contado. Conta conhecida sem IP continua sendo contada por conta/versão. Padrões locais: janela de 300 s, limiar de 5 tentativas por conta/versão e 20 por IP, bloqueio de 300 s. Esses limiares são parâmetros iniciais, não política universal para produção/NAT.

O conjunto conserva somente as tentativas mais recentes até o limiar configurado: é um sinal limitado de limiar, não contagem total/histórico de falhas. ZADD NX impede dupla contagem do attemptId mesmo em outro eventId. O horário do Redis define a janela vigente. O bloqueio vence no horário da tentativa mais recente que compõe o limiar mais a duração configurada; um evento atrasado/repetido não recebe prazo novo a partir da ingestão. Um bloqueio ativo nunca tem seu prazo reduzido por mensagem antiga. Contadores expiram após janela mais 60 s de tolerância.

Login suspeito produz apenas sinal temporário por sid, com prazo a partir do horário do evento (300 s por padrão). Não cria bloqueio administrativo, IP bloqueado ou afirmação de fraude. Eventos mais de 60 s no futuro são recusados antes de aplicar efeitos; observações fora da janela/prazo não ressuscitam bloqueios ou sinais expirados.

## Atomicidade e recuperação

Um script Lua por mensagem usa o hash slot `{block-policy}`, com chaves de efeito e marcador eventId. O fingerprint SHA-256 é dos bytes originais. O script valida tipos e estados usados antes de mutações, aplica efeitos e grava conclusão por último; sorted sets por attemptId e atualizações monotônicas permitem repetir efeitos depois de falha parcial antes da conclusão. Não se declara rollback universal do Lua nem exatamente uma vez.

Marcadores de eventId expiram em sete dias; efeitos versionados não expiram e efeitos temporários seguem seu prazo original. Repetição após a janela de deduplicação continua protegida pelas versões, attemptId/janela e deadlines. Mudanças de limiares/janelas não reconstroem tentativas já descartadas: ajuste exige considerar aquecimento e retenção disponível.

Namespaces: `securegate:{block-policy}:identity`, `policy`, `risk`, `attempts:account`, `attempts:ip`, `blocked:account`, `blocked:ip`, seguidos de SHA-256 do sujeito; conclusões usam `event:<eventId>`. Conta é identificada por `userId:version`, IP por literal canônico e risco por sid. Política guarda hash de resource/action, sem duplicar seu documento. O número de entradas por contador é limitado, mas o total de sujeitos/marcadores cresce com o tráfego; monitorar memória e evitar eviction continua necessário.

## Limites

Não há consulta dessas projeções pelo IAM nesta entrega. A 7C integrará decisões/versões atuais, falha do Redis e rate limiting no caminho HTTP; a 7D medirá a propagação. Não há reconstrução completa após perda dos dados Redis, proteção contra administrador que edite chaves ou prova de alta disponibilidade. O hash slot único é adequado à validação local, sem promessa de escala em cluster. Limpeza segura dos watermarks persistentes e de tombstones continua exigindo limites confiáveis de replay/grants e reconciliação.

Referências: [guia](../runbooks/enforcer-bloqueio-politica-local.md) e [evidências 7B](../evidencias/etapa-07b.md).
