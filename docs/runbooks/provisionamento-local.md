# Provisionamento administrativo local

## Iniciar

Na raiz, com JDK 21 e Docker disponíveis:

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\mvnw.cmd verify
.\infra\scripts\Start-IamCore.ps1 -EnableBootstrap
```

`Initialize-Local.ps1` adiciona `IAM_BOOTSTRAP_TOKEN` aleatório a um `.env` antigo sem alterar as credenciais existentes. Reexecuções preservam o token. A administração continua desligada em `.env` por padrão; o switch `-EnableBootstrap` ativa o mecanismo somente no processo iniciado pelo script.

Sem o switch, `/api/admin/**` fica bloqueado com 403. Com bootstrap ativo, essas rotas exigem `Authorization: Bearer <token>` e retornam 401 para token ausente/inválido. Outros caminhos continuam bloqueados, e os três endpoints GET de saúde permanecem públicos. Credenciais não são transmitidas por query string.

## Exemplo de provisionamento

Em outra janela PowerShell, na raiz:

```powershell
. .\infra\scripts\LocalEnvironment.ps1
$settings = Read-LocalEnvironment (Join-Path (Get-Location) '.env')
$headers = @{ Authorization = 'Bearer ' + $settings.IAM_BOOTSTRAP_TOKEN }
$base = 'http://127.0.0.1:8080/api/admin'

# Conta sintética de exemplo; substitua a senha nos seus testes.
$email = 'alice-' + [guid]::NewGuid().ToString('N') + '@example.test'
$user = Invoke-RestMethod -Method Post -Uri "$base/users" -Headers $headers -ContentType 'application/json' -Body (@{
  email = $email
  displayName = 'Alice Example'
  password = 'Example-only-passphrase!'
} | ConvertTo-Json)

$role = Invoke-RestMethod -Method Post -Uri "$base/roles" -Headers $headers -ContentType 'application/json' -Body (@{
  name = 'INVOICE_READER'
} | ConvertTo-Json)

$permission = Invoke-RestMethod -Method Post -Uri "$base/permissions" -Headers $headers -ContentType 'application/json' -Body (@{
  resource = 'invoice'
  action = 'read'
} | ConvertTo-Json)

Invoke-RestMethod -Method Put -Uri "$base/roles/$($role.id)/permissions/$($permission.id)" -Headers $headers
Invoke-RestMethod -Method Put -Uri "$base/users/$($user.id)/roles/$($role.id)" -Headers $headers
Invoke-RestMethod -Uri "$base/users/$($user.id)/permissions" -Headers $headers
```

O resultado de permissões contém `invoice:read`. O exemplo cria catálogos com nomes fixos; uma segunda execução que tente recriá-los retornará 409. Consulte os catálogos para reutilizar seus IDs. Não imprima `$headers`, o token ou o conteúdo do `.env` ao compartilhar diagnósticos.

## Endpoints

Todos os caminhos abaixo são relativos a `/api/admin` e exigem bootstrap ativo e autenticado.

| Método e caminho | Resultado |
| --- | --- |
| `POST /users` | Cria usuário ACTIVE e credencial separada; retorna 201 e Location |
| `GET /users?limit=20&offset=0` | Lista usuários sem credenciais |
| `GET /users/{id}` | Consulta perfil, estado e versão |
| `PUT /users/{id}` | Atualiza e-mail/nome, com `version` |
| `PATCH /users/{id}/status` | Altera estado, com `status` e `version` |
| `PUT /users/{id}/password` | Troca senha com `password` e `version`; retorna 204 |
| `DELETE /users/{id}?version=N` | Desativa logicamente; retorna 204 |
| `POST /roles`, `POST /groups` | Cria catálogo usando `name` |
| `GET /roles`, `GET /groups` | Lista catálogo paginado |
| `GET /roles/{id}`, `GET /groups/{id}` | Consulta item do catálogo |
| `POST /permissions` | Cria permissão com `resource` e `action` |
| `GET /permissions`, `GET /permissions/{id}` | Lista/consulta permissões |
| `PUT/DELETE /roles/{roleId}/permissions/{permissionId}` | Concede/revoga permissão de um papel |
| `PUT/DELETE /users/{userId}/roles/{roleId}` | Concede/revoga papel direto |
| `PUT/DELETE /groups/{groupId}/members/{userId}` | Adiciona/remove membro |
| `PUT/DELETE /groups/{groupId}/roles/{roleId}` | Concede/revoga papel de grupo |
| `GET /users/{userId}/permissions` | Permissões efetivas, sem duplicação |

PUT/DELETE de relações retornam 204; repetições sem mudança não geram nova auditoria. Catálogos nesta etapa oferecem criação e consulta. Alteração/remoção de definições de catálogo poderá ser adicionada como evolução.

## Validações, estado e concorrência

- E-mail normalizado em minúsculas e único; nome de exibição de 1 a 120 caracteres.
- Senha de 12 a 128 caracteres, preservando espaços de uma passphrase, mas rejeitando valor inteiramente branco.
- Papéis/grupos usam identificadores como `INVOICE_READER` ou `FINANCE`, até 64 caracteres, iniciando por letra e aceitando letras, números e sublinhado.
- Segmentos de permissão são identificadores em minúsculas, como `invoice:read`, com letras, números, hífen ou sublinhado; sem curingas.
- Páginas aceitam `limit` de 1 a 100 e `offset` de 0 a 10000.
- Usuários começam em ACTIVE, versão 0. ACTIVE e LOCKED podem alternar; DISABLED é terminal.
- Cada mudança de perfil, estado ou senha incrementa a versão. Após troca de senha ou DELETE, consultar o usuário para obter a versão atual.
- Uma versão antiga retorna 409; carregar o estado atual antes de decidir repetir a operação.
- Usuário LOCKED/DISABLED ou inexistente tem conjunto de permissões efetivas vazio. Concessões a usuários inativos são rejeitadas; revogações são permitidas.

Exemplo de bloqueio usando a versão atual:

```powershell
$current = Invoke-RestMethod -Uri "$base/users/$($user.id)" -Headers $headers
Invoke-RestMethod -Method Patch -Uri "$base/users/$($user.id)/status" -Headers $headers -ContentType 'application/json' -Body (@{
  status = 'LOCKED'
  version = $current.version
} | ConvertTo-Json)
```

## Erros e auditoria

Erros usam Problem Details com ID de correlação e sem valores de senha/hash/token. HTTP 400 indica entrada inválida, 401 falha de autenticação bootstrap, 403 rota bloqueada, 404 recurso administrativo indisponível, 409 conflito de unicidade/versão e 500 falha interna.

Toda mutação efetiva precisa registrar auditoria para concluir. Se a gravação de auditoria falhar, usuário/credencial/atribuição são revertidos na mesma transação. A tabela inicial bloqueia UPDATE/DELETE/TRUNCATE. Particionamento, consultas públicas de auditoria e isolamento de privilégios serão evoluídos na etapa 8.

## Testes e encerramento

```powershell
.\mvnw.cmd -B -ntp clean verify
```

O relatório de cobertura fica em `iam-core/target/site/jacoco/index.html`. O mínimo de 80% de linhas é verificado por pacote de domínio/aplicação de Identity e Access. Os testes HTTP usam bancos temporários e não dependem de habilitar o bootstrap no banco de desenvolvimento.

Encerrar o Java com Ctrl+C. Iniciar sem `-EnableBootstrap` desliga novamente a administração naquele processo. A senha bootstrap não é um JWT nem um login de usuário; OAuth/OIDC será entregue na etapa 4.
