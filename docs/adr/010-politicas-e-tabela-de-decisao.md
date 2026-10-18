# ADR-010 — Fundação de políticas e combinação de efeitos

- Data: 18/10/2026
- Estado: aceito para a entrega 5A

## Contexto

A etapa 4 disponibiliza OAuth/OIDC. A etapa 5 precisa definir como políticas são armazenadas e combinadas antes de integrar risco, MFA e os pontos de aplicação. A prioridade numérica não pode permitir que uma autorização ampla ignore uma proibição ou redução de privilégios.

## Decisão

Persistir políticas em `iam_policy.policies`, com migração V5: UUID, nome único, prioridade de 0–1000, estado habilitado, versão otimista e documento JSONB. Criação começa em versão 0; cada PUT substitui a definição e incrementa a versão, exigindo a versão anterior. Nome e target possuem limites e gramática explícita. Não há remoção física pela API; desabilitar usa o mesmo PUT versionado.

A gramática inicial tem `schemaVersion: 1`, `resource`, `action`, `effect`, `rolesAnyOf`, `allowedScopes` e `maxAccessTokenTtlSeconds`. Recurso/ação usam igualdade exata ou `*` para o campo inteiro. Não há glob parcial, script, SpEL, regex configurável ou acesso a classes Java. `rolesAnyOf` vazio aplica a qualquer identidade ACTIVE; caso contrário, pelo menos um papel atual precisa corresponder. Máximo de 20 papéis por regra e dez escopos. Papéis diretos e herdados de grupos vêm do contexto Access pela porta `EffectiveRoles`, disponível mesmo com OAuth desabilitado.

O codec usa leitura estrita, rejeitando propriedades desconhecidas no documento, valores inválidos, versões não suportadas e coerção de strings para números/enums. Documento tem limite de 8192 caracteres; o banco também restringe JSON objeto e versão de esquema. Regra armazenada inválida causa erro e interrompe a avaliação, em vez de ser ignorada.

`ALLOW_LIMITED` exige escopos não vazios e TTL inteiro entre 30 e 300 segundos. Os demais efeitos exigem escopos de restrição vazios e TTL ausente/nulo. Para exigir MFA e limites simultaneamente, usar regras correspondentes de ambos os efeitos.

## Combinação

Todas as regras habilitadas e correspondentes são consideradas. A prioridade maior ordena a explicação, seguida de UUID para desempate; não elimina regras mais restritivas com prioridade menor.

| Combinar | ALLOW | ALLOW_LIMITED | ALLOW_WITH_MFA | DENY |
| --- | --- | --- | --- | --- |
| ALLOW | ALLOW | ALLOW_LIMITED | ALLOW_WITH_MFA | DENY |
| ALLOW_LIMITED | ALLOW_LIMITED | ALLOW_LIMITED | ALLOW_WITH_MFA | DENY |
| ALLOW_WITH_MFA | ALLOW_WITH_MFA | ALLOW_WITH_MFA | ALLOW_WITH_MFA | DENY |
| DENY | DENY | DENY | DENY | DENY |

Ausência de correspondência resulta em `DENY/NO_MATCH`. Qualquer DENY correspondente resulta em `DENY/EXPLICIT_DENY`. As restrições de todas as regras limitadas são intersectadas com os escopos solicitados, sem ampliá-los; vale o menor TTL. Interseção vazia resulta em `DENY/NO_PERMITTED_SCOPES`. Um ALLOW simples não aumenta o escopo solicitado nem remove restrições.

Se MFA for exigido, o efeito é `ALLOW_WITH_MFA`, com `mfaRequired: true`, mantendo os limites calculados. Isso é um requisito pendente, sem prova de fator e sem concessão de acesso. Sem MFA, o efeito é ALLOW_LIMITED quando houver limites e ALLOW nos demais casos. DENY retorna escopos vazios e TTL zero. Sem restrição de TTL, a recomendação usa o teto atual de 300 segundos.

A resposta inclui códigos de motivo e IDs/versões de todas as políticas correspondentes, inclusive as que participam de conflitos. Não envia os documentos na avaliação. Identidade inexistente/inativa recebe DENY com `IDENTITY_INACTIVE`. Mais de 1000 políticas habilitadas candidatas para o target recebe DENY com `POLICY_LIMIT_EXCEEDED`; o repositório busca no máximo 1001. O limite é aplicado antes do filtro por papéis e nunca permite avaliar apenas um subconjunto que possa omitir DENY.

## Administração e auditoria

CRUD delimitado a criar, ler/listar e substituir/desabilitar em `/api/admin/policies`, sob o bootstrap existente. `POST /api/admin/policies/evaluate` é uma simulação administrativa e recebe usuário, recurso, ação e escopos solicitados. Papéis são resolvidos no servidor; não se aceita comprovação de MFA ou score de risco pelo pedido. Não é um endpoint público de autorização de recursos.

Criação/edição registra `policy.created`/`policy.updated` na mesma transação da política, com versão, efeito, estado, ator e correlação, sem documento completo. Falha de auditoria faz rollback; atualização concorrente ou nome duplicado retorna 409. Sem registro de Outbox nesta entrega: propagação de eventos será implementada na etapa 6. Leituras e simulações não criam eventos de concessão de acesso.

## Limites e sequência

Esta entrega não muda login, consentimento, grants, claims ou TTL dos tokens existentes. A avaliação é uma recomendação com leituras do estado atual; não é uma garantia de snapshot imutável entre Identity, Access e Policy. Sua integração com operações de segurança exigirá a revalidação correspondente.

A 5B implementará condições ABAC e risco determinístico com sinais confiáveis; a 5C, TOTP/recuperação/step-up; a 5D, aplicação no login, emissão e recursos. Não marcar MFA como satisfeito por um boolean fornecido pelo cliente. Escopos das políticas não substituem a validação de escopos registrados/consentidos do protocolo OAuth. Permissões do catálogo Access não criam automaticamente políticas ALLOW: nesta versão, concessão exige política correspondente.

JaCoCo passa a exigir o mínimo de 80% de linhas por pacote de domínio/aplicação de Policy, preservando os demais contextos. Fontes e testes mantêm as regras ArchUnit existentes.

Referências: [roteiro](../PLANO-DE-IMPLEMENTACAO.md), [guia administrativo](../runbooks/politicas-local.md) e [evidências 5A](../evidencias/etapa-05a.md).
