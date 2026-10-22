# ADR-036 — PostgreSQL temporário pelo Vault e migração separada

- Data: 20/10/2026
- Estado: aceita para a entrega local 9A2b

O ciclo de lease único descrito nesta decisão foi substituído pela [ADR-037](037-renovacao-e-troca-de-pools.md) na 9A2c; as decisões de migração separada e isolamento do AppRole permanecem.

## Decisão

O modo opcional `IAM_DATABASE_VAULT_ENABLED=true` cria o DataSource principal por AppRole e `GET database/creds/iam-runtime`. O Vault emite um login `sg_` seguido de vinte caracteres alfanuméricos, membro de `securegate_iam_runtime`, com atributos restritos, limite de vinte conexões e `VALID UNTIL`. O pool Java tem no máximo dez conexões. As contas de ingestão/manutenção de auditoria continuam independentes.

O AppRole **iam-runtime** recebe somente seu KV sem senha PostgreSQL, emissão de seu papel de banco e as operações Transit já usadas pelo IAM. Não recebe acesso ao KV proprietário, configuração do Database Engine ou outros papéis. O bootstrap KV seleciona `secret/data/securegate/iam-runtime` nesse modo e importa apenas Redis/broker. Login KV, assinatura Transit e banco usam tokens separados; revogar o token transitório de leitura KV não revoga a credencial do banco.

O processo de execução não aplica Flyway: exige `spring.flyway.enabled=false`, e o script local configura isso automaticamente. `java -jar ... --migrate-only` aplica migrações e o perfil NOLOGIN com credenciais administrativas `IAM_MIGRATION_DATABASE_*`, sem iniciar Spring/Tomcat. O SQL do perfil da 9A2a é empacotado como recurso; continua havendo provisionamento administrativo separado para uso manual.

## Emissor e provisionamento

`Provision-VaultDatabase.ps1` provisiona `securegate_vault_database`, um login dedicado ao plano administrativo do Vault, sem superusuário, propriedade de objetos ou criação de bancos. Ele possui CREATEROLE, ADMIN no grupo runtime sem INHERIT/SET desse grupo, e pg_signal_backend para terminar sessões não privilegiadas. Isso é uma conta administrativa sensível, não um usuário comum da aplicação; o cluster deve ser dedicado/confiável. CREATEROLE e pg_signal_backend não são isolamento completo por banco.

A configuração usa `postgresql-database-plugin`, apenas o papel `iam-runtime`, SQL versionado de criação/renovação/revogação e nome de conexão `securegate-runtime`. A senha inicial aleatória do emissor existe apenas no ambiente/JSON administrativos e é imediatamente rotacionada pelo endpoint `database/rotate-root/securegate-runtime`, sem arquivo local. Não rotacionar a senha proprietária usada pelo migrador. Reaplicar o provisionamento reconcilia a senha do emissor; executar em janela de manutenção, com os processos runtime parados.

## Lease e falhas

TTL padrão de dez minutos e máximo de uma hora. O Java aceita leases de 10–3600 segundos e calcula um limite monotônico dois segundos antes do menor prazo entre token pai e credencial. Antes/depois de emprestar uma conexão verifica esse limite; um agendador também fecha o pool, incluindo conexões emprestadas. Não confiar somente em VALID UNTIL, que não encerra sessões PostgreSQL já autenticadas.

Fechar o pool revoga seu token pai, permitindo ao Vault revogar os leases filhos. A revogação SQL desativa o login, termina suas sessões e remove o papel; não usa DROP OWNED ou privilégios de proprietário. Erros externos são substituídos por mensagens genéricas sem causa, e representações textuais são redigidas. Falha de revogação incrementa `iam.database.vault.revocation.failures`; o pool local permanece fechado e o lease continua sob responsabilidade do Vault. Não há fallback de senha local nem reabertura automática do pool.

O pool registra contadores `iam.database.vault.leases` com outcome `issued`/`closed`, sem usuário, lease ID ou segredo em tags. Até a 9A2c, cada processo usa apenas um lease e precisa ser reiniciado após seu limite. A entrega **não é uma solução de operação contínua**: renovação, troca de pool, recuperação de revogação/renovação e tolerância operacional integrada permanecem pendentes.

## Limites

Modo local desabilitado por padrão; o caminho anterior continua disponível. As novas URLs JDBC recusam userinfo, query e fragmento para evitar credenciais embutidas e usam banco/host explícitos. A conexão Vault–PostgreSQL do provisionador usa a rede Docker local com `sslmode=disable`; TLS PostgreSQL e configuração de certificados precisam da entrega de hardening, antes de produção. O Vault usa HTTPS fora de loopback. Não se alteram as migrações V1–V17.

O runtime não recebe a senha proprietária, mas os scripts administrativos ainda leem `.env` na estação do operador. Segredos dos clientes ficam na memória do processo. Falhas do banco, TTLs, encerramento de sessões e revogação precisam ser considerados para transações em andamento; não se promete término sem interrupção no vencimento.

Fontes oficiais: [Database Engine](https://developer.hashicorp.com/vault/api-docs/secret/databases), [plugin PostgreSQL](https://developer.hashicorp.com/vault/docs/secrets/databases/postgresql), [opções de associação PostgreSQL 16](https://www.postgresql.org/docs/16/sql-grant.html).
