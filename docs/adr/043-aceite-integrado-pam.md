# ADR-043 — Aceite integrado da jornada PAM (9C3)

## Objetivo

Validar com JARs reais a cadeia OAuth/MFA → pedido → emissão Vault → uso PostgreSQL → revogação/expiração → auditoria produtora/Outbox → RabbitMQ → ingestão dedicada → consulta HTTP central. As entregas anteriores validavam partes dessa cadeia e o reinício do observador; esta entrega cobre credencial real e separação de identidades durante a execução integrada.

## Configuração de teste

Usar somente PostgreSQL, Redis, RabbitMQ e Vault descartáveis. O primeiro startup aplica migrações com a identidade proprietária. Depois, parar o IAM, provisionar os perfis SQL versionados e iniciar com login sintético membro de securegate_iam_runtime e Flyway desabilitado. O login dedicado securegate_audit_ingest_login recebe senha por arquivo temporário; emissão PAM usa AppRole/mount/emissor próprios e TTLs do contrato existente. Não carregar `.env` nem arquivos AppRole locais nos testes.

A consulta central reutiliza o pool dedicado de ingestão conforme a arquitetura da 8C; não é conexão runtime nem leitura da auditoria produtora. Verificar que runtime não lê event_log, que ingestão não lê Outbox e que o login humano de diagnóstico não lê identidades. O operador HTTP usa bootstrap; JWT OAuth de usuário não concede consulta administrativa.

## Correção encontrada

O servidor OAuth dependia literalmente do bean flywayInitializer em dois componentes JDBC. Essa referência impedia startup com migrações aplicadas separadamente e spring.flyway.enabled=false, mesmo com schema válido. Substituir pela anotação DependsOnDatabaseInitialization já usada no bootstrap de auditoria: preservar ordenação quando há inicializador e permitir modo runtime sem Flyway. Não criar inicializador fictício nem conceder DDL ao runtime. O fluxo inicial com Flyway e os startups/reinícios com runtime são exercitados pelos mesmos JARs.

## Cenários de aceite

1. Criar cliente/usuário/políticas por APIs administrativas; autenticar com autorização de código/PKCE e consentimento de pam:request/pam:issue. Recusar pedido sem MFA, cadastrar/confirmar TOTP pela interface autenticada e repetir com prova real da sessão.
2. Criar pedido e emitir senha uma vez por HTTP. Usar a visão de diagnóstico com login real e recusar leitura de identidades; GET de metadados não reentrega senha e repetição de emissão é recusada.
3. Manter sessão PostgreSQL, pausar Vault, revogar administrativamente por HTTP e observar lease conhecido em REVOKING com retry, sem fato de revogação antecipado. Encerrar IAM, retomar Vault e iniciar outra JVM mantendo banco/metadados. Verificar remoção do papel, sessão encerrada e fato central consultável com a correlação da emissão; prazo do pedido permanece inalterado.
4. Com RabbitMQ parado, criar pedido curto e emitir/usar credencial real. Observar expiração/limpeza e fato de prazo do pedido na Outbox não publicada, sem evento central correspondente. Retomar broker, aguardar ingestão e consultar fatos de emissão, expiração lógica e reconhecimento do Vault. Paginar por cursor com janela/filtro constantes e verificar ausência de duplicação entre páginas.
5. Comparar auditoria central/Outbox com senha, usuário SQL e access token reais mantidos em memória, sem registrar os valores nos resultados. As evidências compartilháveis contêm somente nomes dos cenários e resultados.

## Limites

Aceite local, sem CI remota/merge/implantação comprovados. Pausa/retomada não equivale a perda do Vault dev. VALID UNTIL continua sem encerrar sozinho sessão já autenticada sob falha do Vault. Não há SLA, benchmark de carga, reconstrução de órfãos, HA ou recuperação de resposta de senha perdida.

O login runtime do teste é sintético com o grupo restrito, não um lease de banco dinâmico do runtime; renovação de pools permanece coberta pela 9A2c. O processo de teste contém credenciais administrativas do fixture fora do IAM e bootstrap local; não representa isolamento de processos de produção. A etapa 9 ainda depende de KV do enforcer (9A3), hardening e aceite final global.

Referências: [runbook](../runbooks/jornada-pam-integrada-local.md), [evidências](../evidencias/etapa-09c3.md), [guia](../GUIA-DE-IMPLEMENTACAO.md).
