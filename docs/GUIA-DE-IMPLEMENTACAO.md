# Implementação passo a passo — SecureGate IAM

Atualizado em 20/10/2026. Este guia consolida a sequência de construção, as razões das divisões e os caminhos para executar e validar cada entrega. Complementa o [plano com os critérios de aceite](PLANO-DE-IMPLEMENTACAO.md); os detalhes de operação ficam nos guias indicados em cada linha.

## Ponto atual

| Item | Situação registrada |
| --- | --- |
| Última entrega implementada | 9C1 — fatos operacionais PAM, expiração lógica e recusas autenticadas |
| Base anterior ao commit desta entrega | 9B1, feaf718; anteriormente 9A2c, 17aea1c |
| Trabalho incluído no commit desta entrega | 9B2, consolidação documental e 9C1; publicação e CI remota pendentes |
| Branch | feat/pam-operational-audit |
| Última validação do código | clean verify aprovado em 20/10/2026 às 15:17:53 UTC−03, 734 testes, cobertura e arquitetura aprovadas |
| Próxima entrega | 9C2 — recuperação/observabilidade; depois 9C3 — aceite integrado |
| Outras pendências da etapa 9 | KV do enforcer, hardening e aceite integrado |
| Etapas posteriores | 10 — carga/observabilidade; 11 — entrega/hardening; 12 — gateway/demonstração opcional |

Os estados de publicação/CI estão no plano e nas evidências históricas; não houve consulta ao GitHub nesta revisão. Implementação local, commit, push, CI aprovada e merge são marcos distintos. Um teste local aprovado não comprova publicação, merge, implantação ou configuração da stack persistente.

As seções 0–9C1 abaixo descrevem entregas executadas. As divisões 9C2–9C3, 9A3 e 10–12 são **propostas para organizar trabalho ainda pendente**; não representam funcionalidades implementadas. Não renumerar entregas históricas quando surgir uma nova divisão.

## Como usar e manter este registro

O número identifica a etapa principal; letras e números adicionais identificam grupos e entregas menores. Por exemplo, 9A reúne integrações Vault, 9A2 trata banco dinâmico e 9A2a/9A2b/9A2c separam permissões, emissão e ciclo dos pools. Na etapa 5, 5C1 prepara a base criptográfica e 5C2 acrescenta o fluxo MFA. Cada divisão deve produzir algo revisável e verificável, preservando as dependências da entrega seguinte.

1. Ler o objetivo, as dependências e o aceite da etapa no plano.
2. Usar a sequência de implementação abaixo para compreender os componentes e sua ordem.
3. Abrir o guia operacional da entrega para configuração e comandos completos; consultar a ADR para as decisões.
4. Conferir as evidências antes de afirmar que um cenário foi validado. Contagens antigas de testes representam o momento daquela entrega.
5. Em cada nova entrega, atualizar este guia, o plano, o README, a ADR aplicável, o guia operacional e as evidências. Registrar toda nova subdivisão com motivo, dependências, resultado esperado e aceite.
6. Registrar commit/publicação/CI/merge conforme ocorrerem; não marcar etapas futuras como concluídas por antecipação.

Esta é uma sequência histórica de construção, não uma instrução para reaplicar versões antigas sobre um ambiente atual. Para operar hoje, usar o código atual, migrações Flyway completas e os guias atuais de provisionamento. O build usa containers descartáveis e não migra a stack persistente.

## Etapa 0 — Fundação Maven

Dependência: decisões Java 21, Spring Boot, Maven e Vault Transit. Entrega local validada; [evidências](evidencias/etapa-00.md).

1. Registrar stack e organização no [ADR-001](adr/001-stack-e-organizacao.md) e a assinatura no [ADR-002](adr/002-assinatura-jwt-vault-transit.md).
2. Criar POM agregador e módulos security-events, iam-core e policy-enforcer, com versões/dependências centralizadas.
3. Adicionar Wrapper Windows/Unix, Enforcer para Java/Maven e Spotless.
4. Configurar editor, finais de linha, arquivos ignorados e estrutura inicial de documentação/infra.
5. Validar o primeiro build pelo Wrapper e registrar versões, comandos e resultado. O módulo de testes ponta a ponta foi acrescentado depois, na etapa 7D.

## Etapa 1 — Infraestrutura local

Motivo da divisão: separar configuração dos serviços, contratos do broker e inicialização de segredos. Dependência: etapa 0. Detalhes: [infraestrutura](../infra/README.md), [evidências](evidencias/etapa-01.md).

| Ordem | Divisão executada | Passo de implementação | Verificação de saída |
| --- | --- | --- | --- |
| 1 | 1A — Compose/credenciais | Definir PostgreSQL, Redis, RabbitMQ e Vault; gerar credenciais locais fora do Git. | Serviços saudáveis e portas locais definidas. |
| 2 | 1B — RabbitMQ | Versionar exchanges, filas, bindings e DLQ. | Topologia carregada com identidades/permissões apropriadas. |
| 3 | 1C — Vault | Preparar KV, Transit, políticas, AppRoles e arquivos protegidos. | Bootstrap executável sem imprimir segredos. |
| 4 | 1D — Smoke/idempotência | Testar disponibilidade e repetição do bootstrap. | Reaplicação não recria indevidamente chaves/segredos. |

PostgreSQL usa porta de host 15432 e interna 5432. Vault dev perde estado ao reiniciar; os guias de recuperação descrevem suas consequências.

## Etapa 2 — Esqueleto e CI

Motivo: estabelecer segurança, persistência e verificações antes dos casos de negócio. Dependências: 0–1. [Guia](runbooks/iam-core-local.md), [evidências](evidencias/etapa-02.md).

| Ordem | Divisão executada | Passo de implementação | Verificação de saída |
| --- | --- | --- | --- |
| 1 | 2A — Bootstrap | Subir Spring Boot com segurança por padrão, correlação, erros sanitizados e probes. | Rotas não autorizadas recusadas e saúde acessível conforme contrato. |
| 2 | 2B — PostgreSQL/Flyway | Criar schemas de contexto e conexão com banco real. | Migrações aplicadas e repetição sem duplicação. |
| 3 | 2C — Arquitetura/HTTP | Fixar fronteiras de camadas/contextos e testes da aplicação. | ArchUnit e cenários HTTP aprovados. |
| 4 | 2D — CI | Executar Wrapper, formatação, testes e verificações no GitHub Actions. | Registrar execução remota e PR separadamente do aceite local. |

## Etapa 3 — Identity e Access

Motivo: construir a identidade e o RBAC antes de emitir tokens. Dependência: 2. [Provisionamento](runbooks/provisionamento-local.md), [ADR-004](adr/004-identity-access-bootstrap.md), [evidências](evidencias/etapa-03.md).

| Ordem | Divisão executada | Passo de implementação | Verificação de saída |
| --- | --- | --- | --- |
| 1 | 3A — Usuários/credenciais | Criar domínio, portas e persistência; hash Argon2id e ciclo de vida da identidade. | Provisionar e alterar usuário sem expor senha/hash. |
| 2 | 3B — RBAC | Criar papéis, grupos, permissões e atribuições. | Calcular permissões efetivas por vínculos atuais. |
| 3 | 3C — Administração/auditoria | Proteger APIs de bootstrap e auditar mudanças transacionais. | Acesso anônimo recusado; falha de auditoria desfaz alteração. |
| 4 | 3D — Qualidade | Cobrir regras, persistência, HTTP e isolamento arquitetural. | Gates de cobertura e arquitetura aprovados. |

1A–1D, 2A–2D e 3A–3D descrevem a organização interna registrada das entregas 1, 2 e 3; não se presume um PR ou arquivo de evidência separado para cada letra.

## Etapa 4 — OAuth/OIDC e Transit

Motivo: separar custódia das chaves, fluxos de máquina/usuário e ciclo dos tokens. Dependências: 3 e Vault. Cada linha é um passo posterior ao anterior.

| Divisão executada | Sequência de implementação | Operação e evidências |
| --- | --- | --- |
| 4A — Transit/JWKS | Criar porta de assinatura → AppRole/adapter Transit → encoder RS256/kid → JWKS/cache → rotação e falhas. | [Guia](runbooks/transit-local.md), [evidências](evidencias/etapa-04.md). |
| 4B — Client Credentials | Integrar Authorization Server → persistência JDBC → cadastro protegido de cliente → segredo em hash → token/audiência/TTL. | [Guia](runbooks/oauth-client-credentials-local.md), [evidências](evidencias/etapa-04b.md). |
| 4C — Login/PKCE | Integrar Identity → cliente confidencial → redirects exatos/S256 → consentimento CSRF → código de uso único → token com papéis/sid. | [Guia](runbooks/oauth-pkce-local.md), [evidências](evidencias/etapa-04c.md). |
| 4D1 — Ciclo dos tokens | Adicionar famílias de refresh → rotação e limite absoluto → detecção de reuso → introspecção/revogação. | [Guia](runbooks/oauth-refresh-local.md), [evidências](evidencias/etapa-04d1.md). |
| 4D2 — OIDC | Adicionar discovery → ID token Transit/nonce → userinfo → limites de escopo, audiência e identidade atual. | [Guia](runbooks/oidc-local.md), [evidências](evidencias/etapa-04d2.md). |

Aceite: fluxos reais, validação independente de assinatura/JWKS e negativos de token, redirect, audiência, expiração, reuso e Vault indisponível. Não declarar certificação OIDC por esses testes.

## Etapa 5 — Políticas, risco e MFA

Motivo: separar decisão, sinais confiáveis, prova MFA e pontos de aplicação. Dependência: 4.

| Divisão executada | Sequência de implementação | Operação e evidências |
| --- | --- | --- |
| 5A — Políticas | Persistir JSONB/versionamento → gramática restrita → tabela de decisão/default deny → administração e simulação. | [Guia](runbooks/politicas-local.md), [evidências](evidencias/etapa-05a.md). |
| 5B1 — ABAC/risco | Acrescentar condições tipadas → risco determinístico → ausência/expiração conservadoras → simulação identificada. | [Guia](runbooks/abac-risco-local.md), [evidências](evidencias/etapa-05b1.md). |
| 5B2 — Coleta | Vincular sinais ao usuário/versão/sid → confiança expiráveis de navegador/rede → falhas reais e sensibilidade do recurso. | [Guia](runbooks/coleta-risco-local.md), [evidências](evidencias/etapa-05b2.md). |
| 5C1 — Base MFA | Implementar TOTP → proteger semente com Transit AES-GCM → vincular usuário/inscrição. | [Guia](runbooks/mfa-base-local.md), [evidências](evidencias/etapa-05c1.md). |
| 5C2 — Inscrição/step-up | Criar formulários protegidos → ativar fator → recuperação de uso único → limite de tentativas → prova de sessão. | [Guia](runbooks/mfa-local.md), [evidências](evidencias/etapa-05c2.md). |
| 5D1 — Tokens | Reavaliar políticas na emissão/refresh → intersectar escopos/TTL → exigir prova MFA do sid original. | [Guia](runbooks/politicas-tokens-local.md), [evidências](evidencias/etapa-05d1.md). |
| 5D2 — Navegação | Criar desafio/continuação → vincular ao navegador → reavaliar antes de concluir autorização. | [Guia](runbooks/desafio-mfa-local.md), [evidências](evidencias/etapa-05d2.md). |
| 5D3 — Recursos | Mapear operação no servidor → validar token/grant → reavaliar decisão e prova por chamada → auditar acesso permitido. | [Guia](runbooks/recursos-local.md), [evidências](evidencias/etapa-05d3.md). |

Aceite: DENY prevalece, permissões não são ampliadas, simulação não vira autorização e MFA sintético não substitui prova real.

## Etapa 6 — Eventos e Outbox

Motivo: assegurar atomicidade antes de introduzir publicação e recuperação distribuídas. Dependência: 5.

| Divisão executada | Sequência de implementação | Operação e evidências |
| --- | --- | --- |
| 6A1 — Envelope/Outbox | Definir contrato v1 → criar Outbox → gravar evento junto à transação auditada. | [Guia](runbooks/outbox-local.md), [evidências](evidencias/etapa-06a1.md). |
| 6A2 — Produtores | Validar dados → conectar produtores reais → avaliar login uma vez → correlacionar sessão/autorização. | [Guia](runbooks/eventos-seguranca-local.md), [evidências](evidencias/etapa-06a2.md). |
| 6B — Publicação | Adquirir lote com lease/fencing → publicar com confirms/mandatory return → persistir retry → encaminhar DLQ. | [Guia](runbooks/publicacao-outbox-local.md), [evidências](evidencias/etapa-06b.md). |
| 6C — Operação | Medir backlog → reter publicados → colocar/liberar quarentena com histórico → testar recuperação. | [Guia](runbooks/operacao-outbox-local.md), [evidências](evidencias/etapa-06c.md). |

Aceite: evento e mudança de negócio são atômicos; indisponibilidade do broker não causa perda silenciosa nem publicação considerada concluída sem confirmação.

## Etapa 7 — Enforcer e Redis

Motivo: construir projeções e aplicação das decisões antes de medir toda a cadeia. Dependência: 6.

| Divisão executada | Sequência de implementação | Operação e evidências |
| --- | --- | --- |
| 7A — Revogação | Consumir contrato → deduplicar/fingerprint → projetar jti/authorizationId/sid → ACK após efeito → DLQ/recuperação. | [Guia](runbooks/enforcer-revogacao-local.md), [evidências](evidencias/etapa-07a.md). |
| 7B — Bloqueio/política | Consumir mudanças → manter versões/identidade → aplicar limiares e bloqueios temporários. | [Guia](runbooks/enforcer-bloqueio-politica-local.md), [evidências](evidencias/etapa-07b.md). |
| 7C1 — Recurso | Consultar estado distribuído por operação → recusar estado inválido/indisponível → aplicar revogação/bloqueios. | [Guia](runbooks/recurso-enforcement-redis-local.md), [evidências](evidencias/etapa-07c1.md). |
| 7C2 — Autenticação | Aplicar projeções no login/emissão/refresh → calcular risco → exigir MFA real quando necessário. | [Guia](runbooks/autenticacao-enforcement-redis-local.md), [evidências](evidencias/etapa-07c2.md). |
| 7D1 — Baseline | Executar JARs reais → correlacionar Outbox/Redis/HTTP → medir revogação/logout. | [Guia](runbooks/propagacao-ponta-a-ponta-local.md), [evidências](evidencias/etapa-07d1.md). |
| 7D2 — Resiliência | Exercitar duplicação/ordem → interromper dependências → recuperar → medir bloqueios e efeitos finais. | [Guia](runbooks/resiliencia-ponta-a-ponta-local.md), [evidências](evidencias/etapa-07d2.md). |

Aceite: repetição não duplica efeitos; versões antigas não desfazem estado novo; falha de Redis não vira permissão automática. Medições locais não são garantia de escala/SLA.

## Etapa 8 — Auditoria e desempenho

Motivo: separar modelo de ingestão, credenciais, transporte, consulta e operação de dados. Dependência: 7.

| Divisão executada | Sequência de implementação | Operação e evidências |
| --- | --- | --- |
| 8A1 — Base particionada | Criar log mensal e recibos globais → porta de gravação → manutenção opcional. | [Guia](runbooks/auditoria-particionada-local.md), [evidências](evidencias/etapa-08a1.md). |
| 8A2 — Identidades | Criar logins/pools separados → conceder ingestão/manutenção limitadas → testar negações e timeouts. | [Guia](runbooks/identidades-auditoria-local.md), [evidências](evidencias/etapa-08a2.md). |
| 8B — Consumidor | Ingerir lote transacional → reconhecer após commit → deduplicar → DLQ/conflitos/reconexão. | [Guia](runbooks/consumidor-auditoria-local.md), [evidências](evidencias/etapa-08b.md). |
| 8C — Consulta | Acrescentar filtros/índices → limitar janela/tamanho → paginar com cursor estável → proteger API. | [Guia](runbooks/consulta-auditoria-local.md), [evidências](evidencias/etapa-08c.md). |
| 8D1 — Arquivo | Planejar mês elegível → arquivar atomicamente → preservar recibos e eventos tardios. | [Guia](runbooks/arquivamento-auditoria-local.md), [evidências](evidencias/etapa-08d1.md). |
| 8D2 — Medição | Criar massa/harness optativos → medir ingestão/consulta → registrar planos SQL e número de SELECTs. | [Guia](runbooks/medicao-auditoria-local.md), [evidências](evidencias/etapa-08d2.md). |

Aceite: duplicação sem novas linhas, cursor correto com timestamps iguais e contas da aplicação sem alteração/exclusão indevida de auditoria. Proprietário/superusuário do banco continua fora dessa barreira de privilégios.

## Etapa 9 — Vault avançado e PAM

Motivo: isolar credencial inicial, permissões do runtime, ciclo dos pools e credenciais humanas. Dependência: 8. O escopo funcional implementado vai até 9C1; a etapa 9 ainda não está concluída.

| Divisão executada | Sequência de implementação | Operação e evidências |
| --- | --- | --- |
| 9A1 — KV do IAM | Ler AppRole por arquivo → obter snapshot KV antes das conexões → validar campos/precedência → revogar token de leitura. | [Guia](runbooks/vault-kv-local.md), [evidências](evidencias/etapa-09a1.md). |
| 9A2a — Perfil runtime | Criar grupo NOLOGIN → conceder objetos/colunas explicitamente → separar privilégio do trigger → testar sem proprietário. | [Guia](runbooks/permissoes-runtime-local.md), [evidências](evidencias/etapa-09a2a.md). |
| 9A2b — Banco dinâmico | Separar migrador → provisionar emissor/Database Engine → AppRole runtime → criar pool temporário → encerrar no prazo. | [Guia](runbooks/vault-database-local.md), [evidências](evidencias/etapa-09a2b.md). |
| 9A2c — Ciclo contínuo | Renovar token/lease → validar nova geração → publicar troca atômica → drenar/fechar → repetir revogação e recuperar. | [Guia](runbooks/vault-database-renovacao-local.md), [evidências](evidencias/etapa-09a2c.md). |
| 9B1 — Pedido PAM | Criar V18 → exigir pam:request/política/MFA → validar justificativa/TTL → persistir com Outbox → consultar/revogar somente o próprio. | [Guia](runbooks/pedidos-privilegiados-local.md), [evidências](evidencias/etapa-09b1.md). |
| 9B2 — Emissão PAM | Criar V19 → consumir pedido → obter lease dedicado → reautorizar → confirmar/auditar → entregar senha uma vez → revogar com recuperação. | [Guia](runbooks/credenciais-pam-local.md), [evidências](evidencias/etapa-09b2.md). |

### Passo a passo da 9C1

1. Criar V20 com origem de correlação e marcadores de expiração aditivos.
2. Preservar separadamente a correlação de criação do pedido e a de emissão; usar UUID do pedido como fallback para linhas antigas.
3. Gravar marcador, auditoria e Outbox na mesma transação condicional, permitindo retry sem duplicar a transição.
4. Observar o prazo de pedidos também sem emissão PAM habilitada e observar o prazo do lease antes do RPC de revogação.
5. Identificar prazo lógico como LOGICAL_DEADLINE e confirmação do backend como VAULT_ACKNOWLEDGED.
6. Auditar recusas nos pontos de autorização/consumo/confirmação com ator verificado e códigos fixos; não atribuir identidade a token inválido.
7. Testar rollback, repetição/concorrência, correlação, concessões, pausa do Vault e falha fechada da auditoria.
8. Registrar os limites e deixar recuperação/observabilidade e jornada integrada para 9C2/9C3.

Operação: [guia 9C1](runbooks/auditoria-pam-local.md); [ADR-040](adr/040-auditoria-operacional-pam.md); [evidências](evidencias/etapa-09c1.md).

### Passo a passo detalhado da 9B2

1. Criar registro de emissão com chave única por pedido e estados ISSUING/ISSUED/FAILED/REVOKING/REVOKED.
2. Provisionar grupo e visão de diagnóstico sem dados de identidades/auditoria, com emissor SQL próprio.
3. Preparar mount pam-database, papéis de TTL fixo e AppRole iam-pam sem KV/Transit/runtime.
4. Proteger emissão com pam:issue e política de ação issue, além de MFA do sid original do token.
5. Bloquear o pedido em transação curta, validar proprietário/revogação/prazo e registrar consumo. Repetição ou concorrência não emite outra senha.
6. Fora da transação, escolher TTL que caiba no orçamento e solicitar a credencial no Vault.
7. Reavaliar autorização e pedido; confirmar lease/prazo e evento na mesma transação. Só depois permitir a resposta com senha.
8. Persistir apenas metadados. Em falha conhecida, compensar/revogar; em resultado ambíguo, conservar o consumo sem reentregar segredo.
9. Oferecer GET apenas de metadados, revogação própria do pedido e revogação administrativa quando o usuário perdeu a política.
10. Retomar leases conhecidos no worker, repetir falhas e registrar a transição final uma vez.
11. Testar concorrência, isolamento, ACL, privilégio SQL, auditoria indisponível, expiração, pausa/retomada e regressões.
12. Registrar evidências e os limites antes de commit/publicação.

Limites vigentes: senha não é recuperável por GET; resposta perdida exige revogar antes de criar outro pedido. VALID UNTIL controla nova autenticação por senha, não encerra a sessão já autenticada. Sessões ativas podem ultrapassar o TTL com Vault indisponível; sua revogação depende da retomada. Não reutilizar o papel runtime como acesso humano nem tratar diagnóstico como superusuário.

### Próximas divisões propostas da etapa 9

A 9C1 acima foi implementada. As divisões abaixo ainda não foram implementadas; a ordem imediata é 9C2; KV do enforcer precisa ser concluído antes do aceite final da etapa 9.

| Proposta | Passos previstos | Critério de aceite previsto |
| --- | --- | --- |
| 9C2 — Recuperação/observabilidade | Revisar retries e itens problemáticos → medir pendências/falhas → documentar reinício, resultado ambíguo e perda do Vault dev → estabelecer ações do operador. | Falha temporária recuperável, pendências visíveis e nenhuma declaração de revogação física sem evidência correspondente. |
| 9C3 — Aceite integrado | Testar solicitação → emissão → uso → expiração/revogação → auditoria consultável; incluir falhas/reinício e isolamento do runtime. | Jornada reproduzível, eventos consultáveis, limitações e resultados registrados; demais pendências da etapa explicitadas. |
| 9A3 — KV do enforcer | Integrar leitura protegida no startup do enforcer → validar campos/precedência → testar falha sem fallback e documentar operação. | Enforcer pode obter segredos pelo caminho protegido; ativação, limites e recuperação testados/documentados. |

Os números propostos organizam o trabalho futuro; ADRs, migrações e comandos finais serão definidos ao implementá-lo. Não existem evidências de execução dessas subdivisões ainda.

## Etapas 10–12 — Divisão proposta, ainda pendente

As metas e o escopo principal continuam no plano. As divisões abaixo são uma proposta de sequência, sujeita a ajuste documentado conforme os resultados.

| Proposta | Passos previstos | Saída verificável |
| --- | --- | --- |
| 10A — Massa e planos | Preparar identidades/clientes sintéticos → criar planos JMeter reproduzíveis → separar sucessos de negativas esperadas. | Massa/planos versionados sem segredos e smoke de carga executável. |
| 10B — Observabilidade | Definir métricas, logs sanitizados e tracing HTTP/RabbitMQ → correlacionar dependências e filas. | Diagnóstico de latência/erro sem payload sensível. |
| 10C — Medição | Medir token, validação, revogação e auditoria → registrar hardware, duração, percentis e consumo. | Relatórios reais comparáveis às metas; desvios explícitos. |
| 10D — Ajustes | Investigar gargalos → alterar configuração/código com evidência → repetir os cenários afetados. | Resultado antes/depois e limites de capacidade registrados. |
| 11A — Imagens/stack | Criar imagens Java 21 sem root → perfil Compose full → probes e smoke em ambiente limpo. | Stack completa executável conforme guia. |
| 11B — CI e release | Integrar scans/checks → versionar artefatos/imagens → definir publicação GHCR no fluxo de release. | Pipeline verificável, sem publicar imagens de PR como release. |
| 11C — Hardening | Revisar TLS/CORS/cabeçalhos/limites/segredos → threat model/ASVS → revisar permissões e proteção de main. | Controles e riscos residuais documentados e verificados. |
| 11D — Fechamento v1 | Reproduzir instalação limpa → revisar documentação/evidências → preparar release após aceite. | Critérios da v1 atendidos e revisão/publicação registradas quando ocorrerem. |
| 12A — Gateway opcional | Mapear rotas → validar JWT → aplicar revogação/políticas → definir falhas de dependências. | Gateway testado sem reduzir controles dos recursos. |
| 12B — Jornada demonstrável | Reproduzir login/acesso/evento suspeito/bloqueio/negação → preparar coleção de API. | Demonstração repetível com dados sintéticos. |
| 12C — Materiais finais | Atualizar diagramas/ADRs → registrar demonstração → consolidar instruções. | Materiais coerentes com o comportamento implementado. |

## Mapa das migrações atuais

O diretório [db/migration](../iam-core/src/main/resources/db/migration/) contém as fontes versionadas. Scripts administrativos de grants/emissores ficam em [infra/postgres](../infra/postgres/) e não substituem Flyway.

| Migração | Entrega relacionada | Evolução |
| --- | --- | --- |
| V1 | 2 | Schemas dos contextos. |
| V2 | 3 | Identity, Access e auditoria básica. |
| V3 | 4B | Persistência OAuth JDBC. |
| V4 | 4D1 | Famílias/histórico de refresh. |
| V5 | 5A | Fundação de políticas. |
| V6 | 5B1 | Gramática ABAC. |
| V7 | 5B2 | Evidências confiáveis de risco. |
| V8 | 5C2 | Fatores, recuperação e provas MFA. |
| V9 | 6A1 | Outbox transacional. |
| V10 | 6A2 | Observação única de evento de login. |
| V11 | 6B | Leases de publicação. |
| V12 | 6C | Operações/quarentena da Outbox. |
| V13 | 8A1 | Ingestão particionada e recibos. |
| V14 | 8A2 | Manutenção limitada de partições. |
| V15 | 8C | Índices de ator/correlação. |
| V16 | 8D1 | Arquivo mensal de auditoria. |
| V17 | 9A2a | Privilégios do trigger de histórico. |
| V18 | 9B1 | Pedidos de acesso privilegiado. |
| V19 | 9B2 | Consumo e metadados de emissão PAM. |
| V20 | 9C1 | Correlação de origem e observação transacional da expiração PAM. |

Não editar migrações já aplicadas para implementar a próxima etapa. Mudanças novas de schema devem ser aditivas/versionadas e acompanhadas de compatibilidade e atualização das concessões necessárias.

## Passo a passo padrão para toda próxima entrega

1. **Delimitar:** registrar objetivo, dependências, motivo da divisão, resultado observável e o que fica pendente.
2. **Desenhar:** mapear autorização, dados, segredos, transações e comportamento em falha; criar/atualizar ADR quando houver decisão arquitetural.
3. **Preparar dados/infra:** criar migração e scripts administrativos necessários, sem aplicar automaticamente mudanças na stack persistente pelo build.
4. **Implementar:** domínio/aplicação/portas/adaptadores/apresentação conforme o contexto; conservar as fronteiras de arquitetura.
5. **Validar:** testar cenários positivos, negativas relevantes, concorrência e falhas conforme o risco; formatar e executar os gates aplicáveis.
6. **Operar:** escrever comandos, flags, pré-requisitos, estados, recuperação e limites no guia operacional.
7. **Evidenciar:** registrar ambiente, comandos efetivamente executados, resultado, contagem de testes, relatórios e limitações. Não chamar teste planejado de executado.
8. **Atualizar o mapa:** modificar este guia, o plano e README; manter o índice de documentos e links válidos.
9. **Versionar/publicar:** registrar commit, push, PR, CI e merge separadamente, quando autorizados/realizados.

Comandos de desenvolvimento usados pelo projeto, executados na raiz:

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp clean verify
git diff --check
```

A seleção de testes pode ser menor durante a implementação; registrar exatamente o que rodou e concluir os checks necessários antes do aceite. Para mudanças apenas documentais, revisar coerência e links; não apresentar resultados antigos como nova execução de testes.

## Modelo de registro de uma nova subdivisão

```text
Código e título:
Estado: proposta / em implementação / validada localmente / versionada / publicada
Objetivo e comportamento observável:
Motivo da divisão:
Dependências:
Passos de implementação:
Arquivos, APIs, migrações e flags afetados:
Autorização e tratamento de segredos:
Falhas, compensação e recuperação:
Critérios de aceite:
Comandos executados e resultados reais:
ADR, guia operacional e evidências:
Limitações e próximo passo:
Branch, commit, PR, CI e merge, conforme ocorrerem:
```

Todos os registros devem omitir senhas, tokens, chaves privadas, sementes MFA e respostas sensíveis. A documentação de um limite observado faz parte da entrega.
