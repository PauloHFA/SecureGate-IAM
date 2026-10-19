# ADR-013 — Base TOTP e cifra de segredos pelo Transit

- Status: aceita para a entrega 5C1
- Data: 2026-10-18

## Contexto

A 5C acrescentará cadastro de MFA, recuperação e comprovação de step-up. O segredo TOTP precisa ser recuperável para verificar códigos; hash de senha não atende a esse requisito. A chave RSA usada para JWT não deve servir à cifra dos fatores.

## Decisão

Dividir a 5C em entregas verificáveis:

| Entrega | Escopo |
| --- | --- |
| 5C1 | Primitiva TOTP, geração de sementes e proteção com Vault Transit |
| 5C2 | Cadastro/ativação, persistência transacional, recuperação de uso único e prova vinculada à sessão |

A integração das provas às decisões e à emissão continua na 5D. A 5C1 não comprova MFA nem introduz endpoints de cadastro/verificação.

### Perfil TOTP

Usar o [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238): HMAC-SHA1, seis dígitos, período de 30 segundos e origem Unix. A semente tem 160 bits gerados por `SecureRandom`, representados por 32 caracteres Base32 canônicos, sem padding. A entrada de código deve conter exatamente seis dígitos ASCII, preservando zeros iniciais.

Aceitar o intervalo atual e um anterior/posterior. `Totp.match` recebe o último contador aceito e retorna o contador correspondente mais recente estritamente maior. A função é pura: **não consome o código**. Na 5C2, a aplicação deverá bloquear o registro do fator, verificar e persistir o contador na mesma transação da prova e da auditoria. Também deverá persistir o limite de tentativas por conta; a janela de tolerância aumenta o número de códigos candidatos e não substitui esse controle.

O domínio usa apenas APIs do JDK; `javax.crypto` pertence ao JDK e passa a ser permitido explicitamente na regra de arquitetura. Frameworks continuam proibidos no domínio. Os vetores SHA1 de oito dígitos do apêndice B são verificados no perfil de seis dígitos pelo resto módulo 1.000.000, incluindo datas posteriores a 2038.

### Proteção da semente

Criar `transit/keys/securegate-mfa` como `aes256-gcm96`, não exportável e sem backup em texto puro. O bootstrap idempotente preserva chaves existentes e interrompe se os metadados de uma chave MFA existente forem incompatíveis. Essa validação é necessária porque o vínculo AAD exige cifra autenticada. O cliente usa os endpoints oficiais de [cifra e decifra do Transit](https://developer.hashicorp.com/vault/api-docs/secret/transit#encrypt-data), com entrada e dados associados em Base64.

`MfaSecretProtector` vincula a cifra aos dados associados autenticados (AAD):

```text
securegate:mfa:totp:v1:<userId>:<factorId>
```

IDs devem vir do cadastro persistido e confiável, nunca de valores arbitrários fornecidos pelo navegador. O `factorId` identifica uma inscrição e deverá ser renovado em eventual recadastro. Trocar usuário/fator ou adulterar o ciphertext faz a decifra falhar. O banco armazenará somente o ciphertext versionado `vault:vN:...`, juntamente com IDs e estado do fator. A persistência ainda será introduzida na 5C2.

A porta `TransitEncryption`, exposta por Authorization, permite ao adaptador de Identity reutilizar o cliente Transit existente sem importar outra infraestrutura. Assinatura e cifra compartilham sessão AppRole, timeout e limite de concorrência. A chave MFA tem nome fixo; `iam.transit.key-name` continua configurando somente a assinatura RSA. As métricas usam operações fixas `encrypt` e `decrypt`, sem IDs/segredos como tags.

AppRole `iam-core` recebe apenas `update` em encrypt/decrypt da chave MFA. Não pode ler a chave, exportar, configurar, excluir ou rotacionar. `policy-enforcer` não recebe acesso à cifra/decifra. A política operacional separada permite leitura de metadados e rotação, sem ser atribuída à aplicação.

Não há chave local, cache de semente decifrada ou fallback em indisponibilidade. Falhas remotas são convertidas para exceção de mensagem fixa, sem causa/corpo do Vault. Operações continuam limitadas pelo timeout e sem retentativa automática. Strings e cópias internas das bibliotecas permanecem sujeitas à gestão de memória da JVM; limpar arrays temporários não garante apagamento completo da memória. `TotpSecret.toString()` oculta a semente, mas `encoded()` é sensível e deve ser usado somente para cifra ou entrega controlada ao autenticador.

### Rotação e recuperação operacional

Novas cifras usam a versão ativa. Cifras anteriores continuam decifráveis enquanto suas versões forem retidas. A aplicação não faz rewrap nem eleva automaticamente `min_decryption_version`; descartar versões antigas pode inutilizar fatores persistidos. O Vault dev perde as chaves ao reiniciar e não oferece durabilidade adequada para MFA real. Produção exige Vault persistente, TLS e recuperação testada antes de cadastrar fatores.

## Consequências

A entrega permite testar algoritmo e custódia de segredos separadamente do fluxo web. Não marca `ALLOW_WITH_MFA` como satisfeito e não altera login, consentimento, JWT ou refresh. Permanecem na 5C2: reautenticação para cadastro, CSRF, expiração de cadastro pendente, consumo concorrente de TOTP/recuperação, limite de tentativas, auditoria e prova com prazo/vínculo à sessão e versão da identidade. A 5C só terá aceite funcional após essa entrega.

Referências: [roteiro](../PLANO-DE-IMPLEMENTACAO.md), [guia de validação](../runbooks/mfa-base-local.md) e [evidências](../evidencias/etapa-05c1.md).
