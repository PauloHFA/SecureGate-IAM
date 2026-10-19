# Desafio MFA e retorno à autorização — 5D2

O navegador agora é direcionado a `/mfa` quando a autorização exige fator adicional. A comprovação não aprova escopos: depois dela, o fluxo volta às validações e ao consentimento OAuth.

## Preparar

Use o cliente web, usuário Identity e aplicação com Authorization conforme o [guia PKCE](oauth-pkce-local.md). Cadastre política para `resource = oauth:<client_id>`, ação `issue`, conforme o [guia de políticas na emissão](politicas-tokens-local.md). Para testar o desafio, use efeito `ALLOW_WITH_MFA`, `rolesAnyOf` com os papéis desejados ou vazio, `allowedScopes` vazio e sem TTL. Ela pode se combinar com uma política ALLOW_LIMITED correspondente.

Build local com JDK 21 e Docker:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

O build utiliza bancos/Vault temporários. Para usar a aplicação local, prepare a stack e inicie o novo JAR conforme os guias de infraestrutura/PKCE. Não há migração além de V8 nesta entrega.

## Executar o fluxo

1. Inicie a autorização no backend do cliente com state, PKCE S256 e, em OIDC, nonce. Use a mesma aba/sessão de navegador para os passos seguintes.
2. Faça login quando solicitado. Se a política exigir MFA e não houver prova válida, o IAM abrirá `/mfa`, identificando a aplicação que aguarda autorização.
3. Se não tiver fator, confirme a senha e configure o TOTP conforme o [guia MFA](mfa-local.md). Guarde os códigos de recuperação apresentados na ativação. Para fator ativo, use um novo TOTP ou código de recuperação.
4. Clique em **Continuar autorização**. O formulário usa CSRF e um ticket de uso único; não edite parâmetros nem forneça URL de retorno.
5. O IAM revalida o pedido. Confira os escopos e autorize ou negue. Se houver consentimento anterior suficiente, o SAS pode prosseguir conforme suas regras normais.
6. O cliente recebe o código no callback original e realiza a troca com o verifier PKCE. A emissão também reavalia a política e a prova; continue conferindo escopos e validade da resposta.

O botão aparece depois da confirmação/verificação e também em `/mfa` enquanto a prova e o retorno forem válidos. A ativação não redireciona automaticamente, para preservar a exibição dos códigos de recuperação.

Se a prova expirar enquanto o consentimento estiver aberto ou uma política passar a exigir MFA, aprovar o formulário pode abrir outro desafio. A aprovação anterior não é gravada nesse desvio; após o fator, o fluxo pode pedir novo consentimento. **Negar** continua possível sem concluir MFA.

## Prazos e sessões

O retorno vence em cinco minutos, sem renovação por visitas à tela. Há somente um desafio pendente por sessão; uma segunda aba pode substituir o da primeira. Ticket consumido, alterado, vencido ou de outro navegador é rejeitado. Logout, novo login ou mudança da identidade invalidam o vínculo anterior. Reinicie a autorização no cliente quando necessário.

Se o cadastro pedir login recente e sua sessão for antiga, saia, entre novamente e reinicie a autorização. Não há transferência do desafio para o sid novo.

Em `prompt=none`, a navegação não pode abrir MFA: falta de prova resulta em `interaction_required` no callback; falta de login segue `login_required`. O cliente deve reiniciar um fluxo interativo se quiser solicitar o fator. `none` combinado com outro prompt é inválido.

## Diagnóstico

| Situação | Comportamento |
| --- | --- |
| Política ausente/DENY | `access_denied` no callback validado, sem criar desafio |
| Dependência indisponível | 503 local sanitizado, sem aprovação alternativa |
| Retomada sem CSRF | 403, ticket não consumido |
| Ticket sem vínculo/prova/prazo | 400; volte ao cliente e reinicie quando necessário |
| Redirecionamento/PKCE/state/nonce inválidos | Validação do protocolo rejeita o pedido antes de criar retorno |
| Política muda durante MFA | Pedido é reavaliado ao retornar; comprovar fator não supera DENY |

Refresh não abre página de MFA: continua conforme o [guia de refresh](oauth-refresh-local.md), usando o sid do grant original. A [5D3](recursos-local.md) protege o recurso local por operação; a API retorna erro MFA sem abrir navegação. Recursos externos continuam na etapa 7. Consulte [ADR-016](../adr/016-desafio-mfa-na-autorizacao.md), [evidências](../evidencias/etapa-05d2.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
