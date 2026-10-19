# MFA TOTP, recuperação e step-up local — 5C2

Este guia cadastra um fator e comprova MFA na sessão. Desde a [5D1](politicas-tokens-local.md), uma política ALLOW_WITH_MFA exige essa prova na emissão/renovação de tokens de usuário. A [5D2](desafio-mfa-local.md) integra o desafio à navegação da autorização. Cadastrar o fator ainda não impede login somente com senha fora desse fluxo.

## Preparar

Use JDK 21 e Docker. A [infraestrutura local](../../infra/README.md) precisa estar saudável. Atualize o bootstrap do Vault para disponibilizar a chave AES e as permissões:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
.\mvnw.cmd -B -ntp clean verify
```

O build usa PostgreSQL/Vault temporários. Não precisa das credenciais do Compose para os testes. Para executar a aplicação, siga o [guia PKCE](oauth-pkce-local.md), habilitando Authorization, e use um usuário Identity provisionado com senha conhecida. V8 será aplicada ao banco na inicialização do novo JAR. O token bootstrap serve ao provisionamento administrativo, não à tela MFA.

Vault dev não deve custodiar fatores reais: suas chaves são perdidas ao reiniciar. Use dados de teste neste ambiente.

## Cadastrar

1. Entre por `/login` com e-mail e senha. Abra o link **Configurar MFA e verificar fator**, ou acesse `/mfa` no mesmo navegador.
2. Em até cinco minutos do login, confirme a senha atual e inicie o cadastro.
3. Adicione a chave mostrada ao seu autenticador como TOTP, SHA1, seis dígitos e período de 30 segundos. A URI também é mostrada como texto; não há QR code externo.
4. Digite o código do autenticador em até cinco minutos, na mesma sessão. Fechar a página não permite consultar a chave novamente; um novo início com senha válida substitui o cadastro pendente.
5. Guarde os dez códigos de recuperação apresentados. Eles aparecem somente na resposta da ativação; a aplicação persiste apenas hashes. A ativação também cria uma prova válida por cinco minutos.

O código usado para ativar o fator já está consumido. Para comprovar novamente por TOTP, aguarde o próximo intervalo do autenticador. Voltar à página de cadastro ou repetir o POST não recupera semente/códigos.

## Verificar e recuperar

Em `/mfa`, selecione `TOTP` para um novo código ou `RECOVERY` para um dos códigos guardados. Uma verificação válida cria/renova a prova por cinco minutos para a sessão atual. Um código de recuperação funciona uma única vez e reduz a quantidade restante; a recuperação permite comprovar o fator, sem remover ou trocar o TOTP cadastrado.

A prova não se transfere a outro navegador/sid. Logout ou novo login encerram a prova anterior. Mudanças na identidade invalidam a prova e a sessão antiga; entre novamente. Ao expirar, comprove de novo usando um código ainda não consumido.

Há limite de cinco falhas compartilhado pela conta, inclusive entre navegadores e métodos. Na quinta, a resposta é 429 e novas tentativas ficam bloqueadas por cinco minutos. Reiniciar cadastro ou abrir outra sessão não remove o bloqueio. Para iniciar um cadastro depois de uma sessão antiga/bloqueio, faça login novamente para atender ao requisito de login recente.

## Diagnóstico

| Resposta | Ação |
| --- | --- |
| 302 para login / 401 | Entre novamente com identidade ativa e atual |
| 403 | Recarregue o formulário; POST exige o token CSRF da sessão |
| 400 | Confira senha/login recente, sessão/prazo do cadastro, método e código; código TOTP já consumido exige novo intervalo |
| 429 | Aguarde cinco minutos; limite é por conta |
| 503 | Confira Vault, bootstrap e AppRole; não substitua por token root nem por chave local |

Não há recadastro de fator ativo, regeneração de códigos ou reset administrativo nesta entrega. Não exclua linhas do banco nem desabilite controles para contornar perda de fatores. Não compartilhe chave, URI, códigos ou credenciais nos logs/issues.

## Aceite técnico

`mvnw.cmd -B -ntp clean verify` cobre o fluxo HTTP, CSRF, cifra, vínculo/expiração do cadastro, limite de tentativas persistido, consumo concorrente de TOTP/recuperação, rollback de auditoria, validade/invalidação das provas e erro sanitizado do Vault, além das regressões anteriores.

Veja [ADR-014](../adr/014-cadastro-mfa-recuperacao-e-step-up.md), [evidências](../evidencias/etapa-05c2.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
