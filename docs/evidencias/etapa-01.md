# Evidências — Etapa 01

- Data: 18/10/2026, America/Sao_Paulo.
- Execução: Codex no ambiente local do projeto.
- Branch: `docs/plano-de-implementacao`.
- Commit: registro incluído no commit da infraestrutura; consultar `git log -- docs/evidencias/etapa-01.md`. Sem publicação remota nesta entrega.
- Resultado: infraestrutura local saudável e smoke test aprovado.

## Ambiente

Windows 11 com Docker Desktop 4.95.0, Docker Engine 29.8.2 e Compose 5.6.0; containers Linux amd64. Os scripts foram executados em PowerShell neste ambiente.

| Imagem | Digest observado |
| --- | --- |
| `postgres:16.15-alpine` | `sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea` |
| `redis:7.4.11-alpine` | `sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499` |
| `rabbitmq:4.3.6-management-alpine` | `sha256:3505b6096cdbc083bcc363e74366f575ecd0c1a55be4dadcbc60fa08e34647e1` |
| `hashicorp/vault:1.21.4` | `sha256:4e33b126a59c0c333b76fb4e894722462659a6bec7c48c9ee8cea56fccfd2569` |

As tags estão fixadas no Compose; os digests registram as imagens efetivamente baixadas. A decisão de versões está no [ADR-003](../adr/003-infraestrutura-local.md).

## Comandos principais

```powershell
.\infra\scripts\Initialize-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml config --quiet
docker compose --env-file .env -f infra/docker-compose.yml pull
docker compose --env-file .env -f infra/docker-compose.yml up -d --wait --wait-timeout 180
.\infra\scripts\Test-Local.ps1
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
```

## Resultados

| Verificação | Evidência observada |
| --- | --- |
| Compose | Configuração válida e quatro serviços `healthy` |
| PostgreSQL | Consulta `SELECT 1` por TCP com senha retorna `1` |
| Redis | SET com TTL, GET e DEL autenticados retornam os valores esperados |
| RabbitMQ | 4 exchanges, 9 filas e 15 bindings conferidos na API do broker |
| Roteamento | Revogação alcança enforcer/auditoria e não alcança bloqueio; bloqueio alcança sua fila |
| DLQ | Mensagem rejeitada sem requeue chega à DLQ correspondente |
| Retry | Após TTL de 5 segundos, mensagem volta somente à fila original |
| Vault bootstrap | Tarefa `vault-init` termina com código 0 |
| Chave | Transit RSA-3072, versão 1, não exportável, sem backup em texto puro |
| AppRole core | Login, leitura do KV próprio e assinatura PKCS#1 v1.5/SHA-256 passam |
| Permissões core | Rotação, exportação e leitura do KV do enforcer retornam 403 |
| AppRole enforcer | Login e KV próprio passam; assinatura é negada com 403 |
| Verificação Transit | Assinatura de conteúdo de teste é verificada pelo endpoint Transit |
| Segredos locais | `.env` e `infra/.local/` reconhecidos como ignorados pelo Git |

O teste de assinatura desta etapa não é emissão de JWT nem validação independente de JWKS; esses critérios pertencem à etapa 4. Os tokens criados pelo smoke test foram revogados ao final.

Os testes de mensagens criaram um vhost temporário com cópia da topologia e o removeram ao terminar. A configuração efetiva do vhost de desenvolvimento também foi inspecionada. Nenhuma mensagem das filas de desenvolvimento foi consumida ou apagada pelo smoke test.

## Repetição e reinício

Reexecutar o preparo preservou o hash do `.env`. Reexecutar `vault-init` preservou a versão e o material público da chave, a versão do segredo KV e os hashes dos quatro arquivos RoleID/SecretID. Nenhuma credencial válida foi regenerada, e a chave não foi rotacionada.

O RabbitMQ foi reiniciado após regenerar as definições locais. A stack voltou ao estado saudável e o smoke test foi reexecutado para validar o carregamento das definições e a autenticação após o reinício.

## Ajustes encontrados na validação

- A porta `5432` do host foi bloqueada pelo Windows. Adotou-se `15432`, disponível, sem alterar outros processos; no container PostgreSQL permanece `5432`.
- O Compose deste ambiente exigiu indicar `.env` explicitamente; os comandos documentados usam `--env-file .env`.
- O smoke test foi ajustado para tratar corretamente respostas JSON de fila vazia no PowerShell, evitando falso positivo de roteamento.

## Artefatos e limites

- Smoke test reproduzível: `infra/scripts/Test-Local.ps1`.
- Log local sem credenciais: `.firecrawl/etapa1-smoke.log`.
- Operação, portas, recuperação e limitações: [guia de infraestrutura](../../infra/README.md).
- Os quatro serviços ficam em execução ao final; o inicializador permanece encerrado com sucesso.
- Vault dev perde seu estado em reinício; o bootstrap deve ser reexecutado e uma nova chave será criada quando a anterior não existir.
- Não foram validados produção, alta disponibilidade, TLS, recuperação de desastre ou integração Spring AMQP/Vault da aplicação.
- Não houve mudança Java nesta etapa; a validação funcional realizada foi a da infraestrutura.

Próxima entrega: etapa 2, esqueleto do `iam-core`, Flyway, ArchUnit, Testcontainers e CI.
