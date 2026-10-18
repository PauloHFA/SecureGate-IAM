# ADR-014 — Cadastro MFA, recuperação e prova de step-up

- Status: aceita para a entrega 5C2
- Data: 2026-10-18

## Contexto

A [5C1](013-base-totp-e-cifra-transit.md) fornece TOTP e cifra Transit. A 5C2 deve transformar essa base em um fator utilizável, garantindo consumo único, recuperação e comprovação vinculada à sessão. A integração às decisões de política continua na 5D.

## Decisão

Disponibilizar `/mfa` como área autenticada da sessão de navegador, com formulários HTML e CSRF. O token bootstrap administrativo e tokens OAuth não substituem essa autenticação. O navegador recebe somente o estado do seu usuário; não há parâmetro de usuário, sid ou versão confiado ao cliente. Esses valores vêm do principal e da sessão emitidos pelo servidor.

### Cadastro e ativação

`POST /mfa/enroll` exige a senha atual e login com menos de cinco minutos, admitindo até cinco segundos de diferença futura do relógio. Apenas contas atuais/ativas podem agir. Não é permitido substituir fator ativo apenas com senha. O cadastro gera um novo ID de inscrição, cifra a semente pelo Transit com AAD usuário/inscrição e persiste estado PENDING, sid, versão da identidade e expiração de cinco minutos.

A resposta exibe uma única vez a chave Base32 e a URI `otpauth`, como texto para configuração manual. Não há serviço externo de QR code. `GET /mfa` não retorna a semente. Um novo início válido substitui um cadastro ainda pendente e invalida a chave apresentada anteriormente, preservando a contagem de falhas.

`POST /mfa/confirm` exige código TOTP válido, cadastro não expirado e os mesmos sid/versão. A ativação persiste o contador aceito, estado ACTIVE, códigos de recuperação, prova e auditoria em uma transação. O código usado na ativação já está consumido. A resposta exibe dez códigos de recuperação apenas uma vez.

### Consumo e limite de tentativas

`POST /mfa/verify` aceita método `TOTP` ou `RECOVERY`. No TOTP, aceita somente contador maior que o último consumido, conforme a janela da 5C1. Cada código de recuperação contém 128 bits aleatórios em Base64url sem padding (22 caracteres). Somente hashes SHA-256 são persistidos; o código é excluído ao ser utilizado. Hash rápido é adequado ao segredo aleatório de alta entropia, sem substituir Argon2id para senhas humanas.

Todas as operações mutáveis bloqueiam a linha do usuário no PostgreSQL com `FOR UPDATE`, revalidando status/versão. Isso serializa o fator por conta, inclusive entre sessões e instâncias que usam o mesmo banco. O bloqueio também coordena com atualizações da identidade. O consumo e a prova fazem parte da mesma transação; falha de auditoria ou cifra impede aceitação/consumo parcial.

Cinco falhas em cadastro/ativação/verificação bloqueiam novas tentativas por cinco minutos. O contador pertence à conta, é compartilhado entre sessões e permanece no banco mesmo quando a resposta é uma rejeição. O primeiro erro após expirar o bloqueio inicia nova contagem; comprovação válida zera o contador. Iniciar novo cadastro com senha válida não zera falhas. Conta sem fator também tem estado NONE para persistir esse limite. Respostas usam erro genérico 400 ou bloqueio 429, sem informar qual parte da validação falhou.

Falha do Transit retorna 503 sanitizado e não conta como código incorreto. A recuperação usa somente hashes do banco e funciona sem decifrar a semente, inclusive durante indisponibilidade do Vault; cadastro/ativação/TOTP dependem do Transit. Não há fallback de cifra local.

### Prova autoritativa

A tabela `iam_session.mfa_proofs` associa sid, usuário, versão da identidade, inscrição ativa, método, instante e expiração de cinco minutos. `MfaProofs.current` exige conta ativa e versão atual, fator ativo, mesmo usuário/sid e prazo válido. Um boolean do cliente ou da sessão nunca é prova.

Reautenticação bem-sucedida remove a prova do sid anterior antes de emitir outro; logout remove a prova da sessão encerrada. `mfa_session_state` mantém um marcador de encerramento: as operações MFA bloqueiam esse registro dentro da transação, e encerramentos o marcam fechado antes de excluir a prova. Assim, uma verificação em andamento termina antes do encerramento, ou encontra o marcador fechado e não pode recriar uma prova válida. Marcadores fechados não são reabertos.

Mudanças de versão/status invalidam consultas de prova imediatamente. Expiração não depende de tarefa de limpeza. A tabela não representa uma autorização para qualquer operação: na 5D, o ponto de aplicação deverá consultar a prova usando o sid confiável, reavaliar políticas/risco e aplicar os requisitos da operação.

### Persistência e exposição

V8 cria `iam_identity.mfa_factors`, `mfa_recovery_codes`, `iam_session.mfa_session_state` e `mfa_proofs`. Semente fica somente cifrada no banco; códigos de recuperação ficam somente com hash. Resultados e registros sensíveis têm `toString` redigido. As páginas usam `Cache-Control: no-store`, CSP restritiva, `Referrer-Policy: no-referrer` e nenhuma navegação externa automática. Segredos são entregues em corpo de POST, sem query string.

Eventos `identity.mfa.enrollmentstarted`, `activated`, `verified`, `recoveryused` e `failed` registram recurso, ator/correlação e método, sem semente, código, hash, ciphertext ou credenciais.

## Limites

O cadastro é opcional nesta entrega. Login, grants e JWT ainda não exigem MFA automaticamente; `ALLOW_WITH_MFA` continua como exigência pendente no motor administrativo até a integração 5D. Prova vale por sessão, sem vínculo adicional à operação nesta versão; esse vínculo deve ser tratado pelo ponto de aplicação quando necessário.

Não há endpoint para remover/substituir fator ativo, regenerar códigos, recuperação por e-mail ou intervenção administrativa. Perder todos os fatores/códigos exige procedimento de recuperação futuro; não existe bypass por senha. A sessão de navegador continua local à instância. Limites adicionais por origem e retenção/expurgo periódico permanecem nas entregas operacionais. Se a resposta com códigos se perder, a lista não pode ser consultada de novo.

Vault dev perde chaves ao reiniciar. Uso com fatores reais exige custódia durável, recuperação testada e proteção de banco/backups e das credenciais AppRole.

Referências: [guia local](../runbooks/mfa-local.md), [evidências](../evidencias/etapa-05c2.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
