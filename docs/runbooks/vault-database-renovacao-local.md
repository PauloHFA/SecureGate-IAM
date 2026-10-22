# Renovação e recuperação do pool — 9A2c

Consultar [ADR-037](../adr/037-renovacao-e-troca-de-pools.md) e [preparação da 9A2b](vault-database-local.md). Executar na raiz do projeto; os exemplos usam a stack local e não substituem hardening de produção.

## Atualizar o ambiente

Parar os processos runtime antes de reaplicar o provisionamento administrativo:

```powershell
.\mvnw.cmd -B -ntp clean verify
docker compose --env-file .env -f infra/docker-compose.yml run --rm vault-init
.\infra\scripts\Migrate-Database.ps1
.\infra\scripts\Provision-VaultDatabase.ps1
.\infra\scripts\Start-IamCore.ps1 -EnableVaultDatabase -EnableBootstrap
```

`vault-init` atualiza a política runtime com a renovação restrita ao prefixo do seu papel. O provisionador reaplica o SQL idempotente de revogação e rotaciona a senha do emissor dedicado, por isso exige a janela de manutenção. O migrador continua separado do processo web. Não há nova versão Flyway nesta entrega.

O serviço agora renova token/lease automaticamente. Não é mais necessário reiniciar a cada dez minutos. O TTL padrão/máximo continua dez minutos/uma hora no papel de banco e os limites do AppRole continuam controlados pelo Vault. Ao atingir um limite absoluto ou falhar a renovação, o serviço solicita nova credencial por AppRole e valida um novo pool antes da troca.

## Comportamento esperado

- Sem falha: a conexão pode continuar na mesma geração além do TTL original, mediante renovação confirmada.
- Troca: conexões novas usam outra geração; transações já abertas permanecem na anterior por até cinco segundos, respeitando seu vencimento. Conexões não devolvidas são terminadas ao final desse prazo.
- Vault indisponível: o serviço conserva apenas o prazo anteriormente confirmado, não uma extensão presumida. Quando o prazo vence, fecha as sessões e recusa conexões novas. Após recuperar Vault e autenticação, pode reconstruir o pool sem reiniciar o IAM.
- SecretID inválido/expirado: não há fallback. Corrigir a entrega protegida dos arquivos; o worker relê RoleID/SecretID ao criar a próxima sessão. Usar substituição atômica de arquivo para evitar leituras parciais. O modelo local continua SecretID reutilizável; não trocar por uso único enquanto KV/Transit e banco compartilharem arquivos.
- Encerramento: não serão publicadas gerações abertas depois do shutdown; pools são fechados e tokens revogados por melhor esforço.

Não usar esse comportamento para garantir commit de transações longas. O fechamento forçado pode causar rollback, e a camada chamadora precisa tratar a falha sem repetir operações não idempotentes às cegas.

## Acompanhar e recuperar

Verificar `/actuator/health/readiness` e as métricas administrativas protegidas pelo bootstrap. Observar `iam.database.vault.available`, sucessos/falhas de renovação e troca, falhas de revogação e trocas adiadas. Não colocar segredo, usuário temporário ou lease ID em etiquetas/logs.

Se não houver recuperação, verificar saúde/conectividade do Vault e PostgreSQL, validade dos arquivos AppRole, renovação restrita em `iam-runtime.hcl`, papel `database/roles/iam-runtime` e acesso do plugin pelo emissor. O controle não executa requisições em paralelo ilimitado: tentativas aguardam a conclusão da anterior. Falha imediata de configuração pode produzir novas tentativas a cada ciclo de 250 ms; corrigir a configuração em vez de manter o serviço indefinidamente nesse estado.

`iam.database.vault.cleanup.abandoned` ou crescimento de `rotations{outcome=deferred}` indica que a retirada não foi concluída dentro do limite local. Investigar tokens/leases e sessões no Vault/PostgreSQL administrativamente. O runtime não tem permissão para listar ou forçar revogação de leases de outros serviços. Não usar force revoke para esconder falha de limpeza do banco. Há no máximo três gerações no total, com até duas em retirada enquanto uma está ativa. Se a ativa também vencer, as três podem aguardar limpeza sem abrir outra geração; a recuperação depende da limpeza ou do prazo conhecido liberarem espaço.

Pausa/retomada do Vault preservando estado é coberta pelos testes. Perda do Vault dev não é equivalente: reexecutar bootstrap/provisionamento, revisar logins órfãos e considerar a perda das chaves Transit; não se promete recuperar dados cifrados com chaves perdidas.

## Validação reproduzível

`mvnw.cmd -B -ntp clean verify` usa Vault/PostgreSQL descartáveis. Os testes usam TTLs curtos somente nos containers de teste; não alteram o JSON produtivo para dez segundos. Cobrem token e lease renovados além do prazo inicial, troca no limite absoluto, transação fixada na geração antiga, fechamento de conexão não devolvida, pausa do Vault até expiração, falha/repetição de revogação, recuperação no mesmo processo, login removido administrativamente e limite de gerações com revogação negada/restaurada. Consultar [evidências](../evidencias/etapa-09a2c.md).
