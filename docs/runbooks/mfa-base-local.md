# Validar a base MFA TOTP — 5C1

Esta entrega valida o algoritmo e a proteção da semente. Ainda não há tela/API para cadastrar MFA, recuperar acesso ou produzir uma prova de step-up. O login permanece no fluxo anterior; a próxima entrega funcional é a 5C2.

## Build com infraestrutura temporária

Pré-requisitos: JDK 21 e Docker Engine. Na raiz:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

Os testes iniciam PostgreSQL e Vault temporários, sem precisar de `.env`, credenciais reais ou do Compose persistente. O teste `TransitSigningIT` cria ambas as chaves e usa a política real `infra/vault/iam-core.hcl` para o AppRole da aplicação.

O aceite verifica os vetores oficiais SHA1 no perfil de seis dígitos, zeros iniciais, fronteiras de tempo, tolerância de um intervalo, filtro por contador aceito, entradas inválidas, cifra autenticada e troca de vínculo usuário/fator. Também verifica rotação AES com leitura da versão anterior, restrições das políticas, indisponibilidade do Vault, limites de concorrência/timeout e mensagens sanitizadas. A comprovação de consumo único concorrente do fator será feita com persistência na 5C2.

Sem Docker, é possível executar os testes unitários e de arquitetura:

```powershell
.\mvnw.cmd -B -ntp test
```

Esse comando não substitui o aceite de integração.

## Atualizar a infraestrutura de desenvolvimento existente

Com a stack já em execução, reaplique o bootstrap para criar a chave AES e atualizar as políticas:

```powershell
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
.\infra\scripts\Test-Local.ps1
```

O script preserva chaves existentes e cria `securegate-mfa` apenas quando ausente. O smoke test usa dados sintéticos, valida metadados/cifra/decifra pelo AppRole, nega rotação/exportação para `iam-core` e nega acesso MFA ao `policy-enforcer`. Não é necessário usar sementes pessoais nem imprimir tokens. Para uma instalação nova, siga primeiro o [guia da infraestrutura](../../infra/README.md).

## Diagnóstico e limites

- Erro MFA após atualizar uma instalação: confira se `vault-init` terminou com código 0 e se o AppRole contém a política atual. Não substitua a credencial da aplicação por token root.
- Ciphertext não decifra: confira o usuário e o ID da inscrição originais; AAD diferente é rejeitado. A perda da chave também impede a recuperação do segredo.
- Vault indisponível ou entrada remota inválida: a porta retorna erro sanitizado; não use geração/cifra local como fallback.
- Rotação: versões anteriores precisam ser retidas enquanto existirem fatores cifrados com elas. Rewrap e retenção automatizados permanecem fora desta entrega.
- Testes não habilitam MFA para contas reais. Ainda não há migração de fatores nem comprovação de MFA em sessão/token.

Consulte [ADR-013](../adr/013-base-totp-e-cifra-transit.md), [evidências 5C1](../evidencias/etapa-05c1.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
