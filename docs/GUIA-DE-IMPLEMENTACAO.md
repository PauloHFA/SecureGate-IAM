# Implementação passo a passo — SecureGate IAM

Atualizado em 21/10/2026. Este guia consolida a sequência de construção, as razões das divisões e os caminhos para executar e validar cada entrega. Complementa o [plano com os critérios de aceite](PLANO-DE-IMPLEMENTACAO.md); os detalhes de operação ficam nos guias indicados em cada linha.

## Ponto atual

| Item | Situação registrada |
| --- | --- |
| Última entrega implementada | 10B3c3b — tracing da auditoria IAM em lote |
| Base desta entrega | 103553e — consumo no enforcer |
| Trabalho local | Implementação local; incluída neste commit; push pendente |
| Branch | feat/outbox-trace-context |
| Validação da entrega | BUILD SUCCESS às 21:36:26 UTC−03: 845 testes, zero falhas/erros/skips; gates atendidos; ressalva de shutdown do fork IAM/Hikari nas evidências |
| Próxima implementação | 10B4 — backend/exportação e coleta conjunta |
| Pendências seguintes | Aceite global 10B; 10C–10D; publicação/CI |
| Etapas posteriores | 10B–10D — observabilidade/medição/ajustes; 11 — entrega/hardening; 12 — gateway opcional |

Os estados de publicação/CI estão no plano e nas evidências históricas; não houve consulta ao GitHub nesta revisão. Implementação local, commit, push, CI aprovada e merge são marcos distintos. Um teste local aprovado não comprova publicação, merge, implantação ou configuração da stack persistente.

As seções 0–9, incluindo 9A3, 9C3 e a revisão 9D, abaixo descrevem entregas executadas. A 10A1, a 10A2a, a 10A2b1–3, a 10B1, a 10B2a, a 10B2b1, a 10B2b2a, a 10B2b2b, a 10B2b2c, a 10B2b2d, a 10B3a, a 10B3b1, a 10B3b2, a 10B3c1, a 10B3c2, a 10B3c3a e a 10B3c3b também estão implementadas abaixo. As demais divisões 10B4–12 são **propostas para organizar trabalho ainda pendente**; não representam funcionalidades implementadas. Não renumerar entregas históricas quando surgir uma nova divisão.

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

### Passo a passo da 9C2a

A 9C2 foi dividida para validar a fila de credenciais antes de mudar o observador de pedidos. A 9C2a não encerra o grupo 9C2 nem o aceite da etapa 9.

1. Identificar o bloqueio de lotes por dez itens antigos que falham repetidamente.
2. Criar V21 aditiva com contador, próxima tentativa e último código/instante de falha de limpeza; reaplicar o perfil runtime.
3. Persistir intervalos de 2, 4, 8, 16, 32 e 60 s, conservando o teto e o lease após saturar o contador.
4. Comparar contador/estado na atualização, impedir recuo do prazo e selecionar somente itens elegíveis com ordenação que permita avanço de outros itens.
5. Manter expiração, confirmação de revogação e Outbox no contrato transacional da 9C1; nunca reemitir na manutenção.
6. Coletar snapshot a cada cinco segundos e expor pendentes, elegíveis, adiados, retries, resultados incertos e idade desde o claim mais antigo. Sinalizar consulta indisponível com NaN/disponibilidade zero e preservar horário do último sucesso.
7. Testar banco real, dez falhas/itens posteriores, recriação de serviço, concorrência, fronteira de tempo e rollback da auditoria; validar permissões e regressão PAM com Vault real.
8. Documentar reinício somente do IAM, recuperação de dependências e investigação administrativa de resultado incerto/perda de Vault dev; não automatizar limpeza privilegiada nem excluir metadados.

Operação: [guia 9C2a](runbooks/recuperacao-pam-local.md); [ADR-041](adr/041-recuperacao-da-limpeza-pam.md); [evidências](evidencias/etapa-09c2a.md).

### Passo a passo da 9C2b

1. Identificar que a primeira falha de auditoria interrompia o lote de vinte pedidos vencidos.
2. Criar V22 aditiva e concessões de coluna para contador, próxima tentativa e último código/instante de falha da observação.
3. Isolar cada pedido em transação de marcador/fato/Outbox; após rollback, agendar retry em transação separada e continuar o lote.
4. Selecionar somente elegíveis e ordenar por vencimento/próxima tentativa; usar intervalos de 2–60 s, contador saturado e atualização condicional que não reabre revogado/observado.
5. Medir pendentes, prontos, adiados, retries, tempo desde vencimento e disponibilidade/idade do snapshot, sem identidade nos rótulos.
6. Testar vinte falhas/itens posteriores, fronteira de tempo, saturação, concorrência e concessões runtime com PostgreSQL real.
7. Empacotar o IAM e executar outro processo Java: provocar falha de auditoria em banco descartável, confirmar retry/rollback, encerrar JVM, remover falha e iniciar outra JVM no mesmo banco/Vault.
8. Conferir PIDs distintos, agenda, prazo/correlação e uma transição auditada com Outbox; validar a suíte completa e registrar limites.

A 9C2 está implementada localmente por 9C2a/9C2b. O reinício comprova o observador com fixture SQL e emissão desabilitada; a jornada humana HTTP/credencial/ingestão/consulta continua na 9C3.

Operação: [guia de recuperação](runbooks/recuperacao-pam-local.md); [ADR-042](adr/042-recuperacao-do-observador-pam.md); [evidências](evidencias/etapa-09c2b.md).

### Passo a passo da 9C3

1. Executar JARs reais com PostgreSQL/Redis/RabbitMQ/Vault descartáveis, inicialmente migrando com a identidade proprietária.
2. Provisionar os perfis SQL versionados; iniciar IAM com runtime restrito/Flyway desabilitado e ingestão/consulta com identidade/pool dedicados.
3. Corrigir a dependência literal de flywayInitializer no bootstrap OAuth, preservando a ordenação de inicialização quando há migrador.
4. Criar cliente/usuário/políticas por HTTP, autenticar por código/PKCE/consentimento, recusar sem MFA e cadastrar/confirmar TOTP real em memória.
5. Criar pedido, emitir uma vez e usar a credencial PostgreSQL; verificar isolamento SQL, GET sem senha e repetição recusada.
6. Pausar Vault, revogar administrativamente, observar retry sem confirmação antecipada, encerrar IAM e iniciar outra JVM com Vault retomado. Conferir papel/sessão encerrados e fatos centrais com origem/prazo preservados.
7. Parar RabbitMQ, criar/emitir pedido curto e observar expiração/limpeza com fatos na Outbox não publicada. Retomar broker e aguardar todos os fatos esperados, sem pressupor ordem de chegada.
8. Consultar a auditoria central por HTTP/correlação e cursor com janela fixa; verificar que senha, usuário SQL e access token não estão nos fatos.
9. Registrar execução focada, build completo e limites. Não há migração nova; V22 continua atual. Na conclusão da 9C3, KV do enforcer e aceite global ainda estavam pendentes; o KV é implementado na 9A3 abaixo.

Operação: [jornada integrada](runbooks/jornada-pam-integrada-local.md); [ADR-043](adr/043-aceite-integrado-pam.md); [evidências](evidencias/etapa-09c3.md).

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

### Passo a passo da 9A3 — KV do enforcer

1. Reutilizar AppRole policy-enforcer, ACL e KV v2 já provisionados, sem ampliar permissões.
2. Registrar postprocessor no próprio módulo, após carregar configuração e antes dos clientes.
3. Validar flag, endereço, arquivos AppRole e autenticar com timeout limitado.
4. Ler somente secret/data/securegate/policy-enforcer e validar metadados/valores obrigatórios.
5. Permitir apenas senha Redis e usuário/senha RabbitMQ; ignorar campos extras e preservar endpoints locais.
6. Revogar o token de leitura e somente então publicar snapshot prioritário e completo. Abortar em falha sem fallback nem causa sensível.
7. Acrescentar -EnableVaultKv ao launcher, combinável com -EnableConsumer; deixar credenciais locais vazias nesse modo e restaurar o ambiente ao encerrar.
8. Testar falhas/precedência em HTTP controlado e ACL real no Vault descartável.
9. Subir EnforcerApplication real pela descoberta do postprocessor, com credenciais locais inválidas; publicar revogação no broker e comprovar ACK/projeção Redis com credenciais KV.
10. Validar regressões e documentar ativação, rotação coordenada por reinício e limites do snapshot.

Referências: [ADR-044](adr/044-bootstrap-kv-enforcer.md), [operação](runbooks/enforcer-vault-kv-local.md), [evidências 9A3](evidencias/etapa-09a3.md). Sem nova migração; V22 permanece atual. Implementação local em feat/enforcer-vault-kv, sobre a9b67f0, versionada em 6d08c2a; publicação/CI pendentes.

### Passo a passo da 9D — revisão e aceite técnico local

1. Fixar a base 6d08c2a e distinguir validação funcional anterior de nova revisão documental.
2. Confrontar critérios da etapa 9 com ACL, endpoints, persistência, tratamento de erros/segredos e testes atuais.
3. Consolidar os 72 relatórios existentes do build da 9A3: 778 testes aprovados, incluindo 11 E2E; sem executar novo build para documentação.
4. Associar cada controle à prova revisada e ao limite operacional na matriz de aceite.
5. Conferir exclusões/nomes no índice Git e gatilhos da CI, sem imprimir segredos ou afirmar scan completo/publicação remota.
6. Registrar riscos de Vault dev, KV estático, emissão ambígua e sessões PAM durante indisponibilidade.
7. Separar aceite técnico local de aceite global/remoto e verificações de imagens, ainda pendentes.
8. Atualizar documentação, verificar links/diff e preparar a próxima implementação local, 10A.

Referências: [matriz de aceite e reprodução](runbooks/aceite-etapa9.md), [evidências 9D](evidencias/etapa-09d.md). Não há alteração funcional, nova migração ou decisão arquitetural. Revisão versionada em 255fdff, na branch docs/stage-nine-acceptance; publicação/CI pendentes.

A 9D consolida o aceite técnico local, sem declarar aceite global/remoto ou produção. Publicação/CI continuam pendentes; imagens e hardening geral pertencem à etapa 11. A preparação local de carga segue pela 10A; metas só serão declaradas após medição.

## Etapa 10 — massa e planos, implementação iniciada

### Divisão da 10A e passo a passo da 10A1

Separar o primeiro plano máquina das jornadas humanas permite validar massa, asserções, persistência segura de resultados e runner antes de ampliar os cenários. A 10A1 é a base Client Credentials; a 10A2a abaixo acrescenta massa humana/PKCE/replay. Falhas de login, validação/revogação e auditoria continuam na 10A2b. Isso não conclui a 10A nem o aceite de carga da etapa 10.

1. Fixar JMeter 5.6.3 como baseline exercitada, obter distribuição Apache e conferir SHA-512; manter binários fora do Git.
2. Criar New-ClientMass.ps1: IDs prefixados/escopo/audience reproduzíveis, criação pela API administrativa e segredo novo do servidor, fora da janela cronometrada.
3. Receber token por arquivo, limitar alvo a loopback e escrita da massa aos diretórios ignorados; recusar sobrescrita e documentar falha parcial.
4. Versionar client-credentials.jmx com CSV compartilhado, limites, timeout e dois rótulos: emissão válida e invalid_client esperado.
5. Validar status/contrato OAuth, limpar corpos/headers e desabilitar persistência de dados sensíveis no perfil de resultados.
6. Criar Run-ClientCredentials.ps1 com concorrência/iterações/ramp-up limitados, diretório novo, dashboard e metadados.
7. Conferir número de amostras e falhas por rótulo no runner, pois término normal do JMeter não garante aprovação das asserções.
8. Criar JMeterSmoke opt-in: fixture descartável/JARs reais, geração pela API, oito amostras válidas, dashboard e verificação dos artefatos.
9. Corromper a massa na mesma validação e exigir recusa do runner; remover arquivos privados ao encerrar.
10. Validar build padrão sem execução automática/download de JMeter e registrar operação, evidências e limites antes de commit/publicação.

Referências: [operação JMeter](../load-tests/jmeter/README.md), [runbook](runbooks/carga-client-credentials-local.md), [ADR-045](adr/045-base-de-carga-jmeter.md), [evidências 10A1](evidencias/etapa-10a1.md). Sem mudança de aplicação/schema; V22 permanece atual. Versionada em 1e7a201, na branch feat/load-client-credentials-smoke; push confirmado na sessão anterior, PR/CI não verificados.

### Passo a passo da 10A2a — massa humana e PKCE

1. Dividir a 10A2 em massa/PKCE/replay (10A2a) e os demais cenários (10A2b), sem declarar a 10A concluída.
2. Criar New-HumanMass.ps1 para usuários example.test, senhas CSPRNG, clientes confidenciais e políticas ALLOW_LIMITED pela API, fora da parte cronometrada.
3. Restringir a política ao cliente gerado, api:read e TTL de 120 s. Gravar CSV privado com IDs/credenciais nos diretórios ignorados, sem sobrescrever arquivo existente.
4. Criar human-pkce.jmx e roteiro Groovy JSR223: HttpClient/cookies novos por jornada, S256/state novos e formulários CSRF reais.
5. Autenticar/consentir, aceitar consentimento já concedido e validar callback/state sem acessar o callback nem seguir redirects de navegação externos.
6. Trocar código por token, conferir sub/client_id/TTL e manter apenas o contexto necessário em memória para provar replay recusado.
7. Acrescentar Run-HumanPkce.ps1 com rótulos/contagens distintos, dashboard, resultados redigidos e injeção WrongVerifier para testar falha real.
8. Testar JMeterHumanSmoke com fixture descartável, perfil de risco sintético explícito, duas jornadas, dois replays recusados e verifier incorreto com HTTP 400.
9. Procurar credenciais/e-mails sintéticos/Basic/JWT nos artefatos e remover arquivos privados ao encerrar. O gerador não modifica risco/MFA; a configuração sintética pertence à fixture/receita manual dedicada.
10. Validar a suíte padrão separadamente e registrar que elapsed é agregado, sem latência de rede instrumentada, assinatura JWT validada pelo plano ou metas de capacidade comprovadas.

Referências: [operação humana](../load-tests/jmeter/HUMAN-PKCE.md), [runbook](runbooks/carga-humana-pkce-local.md), [ADR-046](adr/046-carga-humana-pkce.md), [evidências 10A2a](evidencias/etapa-10a2a.md). Sem mudança de aplicação/schema; V22 permanece atual. Versionada em 8580b4e, na branch feat/load-human-pkce-smoke; publicação/CI a confirmar.

### Passo a passo da 10A2b1 — falhas de login

Dividir a 10A2b em falhas de login (10A2b1), acesso/revogação (10A2b2) e auditoria (10A2b3), sem declarar a 10A concluída.

1. Criar New-LoginFailureMass.ps1: usuário por API, senha CSPRNG e candidato ausente aleatório example.test, sem cliente OAuth/política extra.
2. Receber token por arquivo e escrever CSV privado em diretórios ignorados, recusando sobrescrita e documentando criação parcial.
3. Criar login-failures.jmx/Groovy com dois rótulos, HttpClient/cookies novos e CSRF real do formulário.
4. Exigir redirect /login?error, alerta genérico sem dados da massa e acesso anônimo a /mfa redirecionado para login.
5. Comparar a mensagem dos dois casos por thread e descartar referência anterior após falha; não aceitar CSRF inválido, rate limit ou indisponibilidade como negativa aprovada.
6. Acrescentar Run-LoginFailures.ps1 com contagens/asserções, dashboard e padrão pequeno de uma thread/uma iteração.
7. Injetar senha correta apenas no controle de detecção; provar sessão autenticada e recusa do runner, sem reutilizar cookies no caso seguinte.
8. Executar JMeterLoginFailureSmoke opt-in com JARs/serviços reais descartáveis; confirmar candidato ausente fora do plano e dois novos fatos auth.login.failed na Outbox.
9. Conferir artefatos sem credenciais/e-mails/IDs da fixture e documentar limites de timing, bloqueios, preparação manual e limpeza.
10. Validar a suíte padrão separadamente, registrar resultados reais e atualizar plano/guia/índices antes de commit/publicação.

Referências: [operação](../load-tests/jmeter/LOGIN-FAILURES.md), [runbook](runbooks/carga-falhas-login-local.md), [ADR-047](adr/047-carga-de-falhas-de-login.md), [evidências 10A2b1](evidencias/etapa-10a2b1.md). Sem mudança de aplicação/schema; V22 permanece atual. Trabalho local em feat/load-login-failures-smoke, sobre 8580b4e, incluída neste commit; push pendente.

### Passo a passo da 10A2b2 — recurso e revogação

1. Criar branch feat/load-resource-revocation-smoke sobre 860d125, mantendo a entrega de login separada.
2. Estender New-HumanMass com IncludeAccountPolicy: política account:self/read ALLOW_LIMITED, api:read e TTL máximo 120 s, apenas em stack dedicada; preservar comportamento sem switch.
3. Reutilizar jornada PKCE, retendo token/expiração em memória somente quando o novo plano solicita; limpar estado no final/falha.
4. Criar plano de cinco rótulos com acesso sem cookies, sujeito conferido e token inválido recusado (401).
5. Revogar com autenticação Basic do próprio cliente e exigir recusa do token original antes da expiração; polling limitado, sem aceitar 403/429/503.
6. Separar aceite de revogação (200) da recusa posterior e da projeção assíncrona; o ledger local também impede acesso.
7. Criar runner com contagens/saídas redigidas e controle SkipRevocation; sem revogação real, o recurso deve continuar 200 e falhar a expectativa de recusa.
8. Executar teste opt-in com JARs/serviços reais, dois usuários/threads, fatos publicados/projetados e verificação de artefatos; repetir JMeterHumanSmoke como regressão compartilhada.
9. Registrar comandos/resultados e limites, sem declarar capacidade/SLA; manter 10A2b3 e 10B–10D pendentes. Não alterar aplicação/schema/ACL/stack persistente; V22 permanece atual.

Referências: [operação](../load-tests/jmeter/RESOURCE-REVOCATION.md), [runbook](runbooks/carga-recurso-revogacao-local.md), [ADR-048](adr/048-carga-de-recurso-e-revogacao.md), [evidências 10A2b2](evidencias/etapa-10a2b2.md). Trabalho local em feat/load-resource-revocation-smoke, sobre 860d125, incluídas neste commit; push pendente.

### Passo a passo da 10A2b3 — auditoria central

1. Criar feat/load-audit-smoke sobre HEAD 860d125 preservando os 16 arquivos da 10A2b2, ainda sem commit. Não produzir commit/push automaticamente.
2. Acrescentar helper audit-only à fixture descartável: provisionar logins de auditoria com script existente, arquivo privado e pool restrito; reiniciar IAM com consumidor/consulta, sem PAM.
3. Criar massa durante o plano: dois usuários por API/iteração, e-mails UUID e senhas CSPRNG; correlacionar os dois fatos reais identity.user.created, sem inserir envelopes diretamente no banco/fila.
4. Consultar API central com janela UTC fixa, tipo/correlação exatos e limit=1. Aguardar primeiro item com cursor por prazo limitado e exigir continuação com segundo evento distinto/recursos exatos.
5. Acrescentar consulta sem credencial e exigir HTTP 401. Recusas inesperadas/indisponibilidade não aprovam o caminho positivo.
6. Criar runner com credencial por arquivo, quatro rótulos/contagens exatas, saídas redigidas e controle WrongCorrelation. 200 vazio deve falhar; cursor ausente não é inventado.
7. Executar smoke com duas threads e serviços reais; comparar oito envelopes centrais/publicados com Outbox fora do tempo medido, incluindo fatos do controle. Conferir artefatos sem segredos/identificadores humanos.
8. Executar regressão de recurso/revogação, validar scripts/XML/documentação, registrar comandos/resultados e limites de medição. Nenhuma alteração de aplicação/schema/ACL/stack persistente; V22 permanece atual.
9. Marcar cobertura funcional dos cenários 10A e seguir para 10B; não declarar capacidade/SLA, ingestão isolada ou aceite global da etapa 10.

Referências: [operação](../load-tests/jmeter/AUDIT.md), [runbook](runbooks/carga-auditoria-local.md), [ADR-049](adr/049-carga-de-auditoria-central.md), [evidências 10A2b3](evidencias/etapa-10a2b3.md). Trabalho atual em feat/load-audit-smoke preserva 10A2b2/10A2b3 incluídas neste commit; push pendente.

### Passo a passo da 10B1 — exportação de métricas do IAM

1. Criar feat/iam-prometheus-metrics sobre 42b9707, com diretório de trabalho limpo, e inventariar meters/flags existentes. Identificar que enforcer é não web.
2. Dividir 10B: 10B1 prepara inventário/exportação IAM; 10B2 tratará coleta/Grafana/enforcer/dependências; 10B3 tratará logs/tracing. Não declarar a 10B completa.
3. Conferir contrato oficial Spring Boot 3.5/Micrometer e acrescentar micrometer-registry-prometheus com versão do BOM.
4. Configurar endpoint desligado por padrão e tag application fixa; histogramas HTTP opt-in com faixa esperada 1 ms–10 s, sem tags de usuário/token/correlação.
5. Criar cadeia exata para /actuator/prometheus: token de leitura próprio por arquivo, digest constante, somente GET e nenhuma sessão; manter admin/JSON metrics/probes separados.
6. Criar inicializador CSPRNG que recusa sobrescrita e flags EnablePrometheus/EnableHttpHistograms no launcher local. Falhar cedo se credencial necessária não existir; não alterar .env/stack persistente.
7. Testar arquivo inválido/ausente, erro sanitizado, padrão desligado, autenticação/duplicidade/POST e isolamento de admin/metrics/env. Conferir JVM/buckets/template de rota sem query/credenciais no scrape.
8. Executar testes focados e build completo com cobertura/arquitetura/E2E; validar scripts em cópia isolada e registrar ocorrências sem inventar causas/resultados.
9. Documentar inventário, consultas propostas e operação, atualizar evidências/índices e avançar para 10B2. Schema/ACL permanecem sem alteração, V22 atual. Nenhum scrape sustentado/capacidade/SLA foi aceito.

Referências: [inventário](OBSERVABILIDADE.md), [operação](runbooks/metricas-prometheus-local.md), [ADR-050](adr/050-exportacao-prometheus-protegida.md), [evidências 10B1](evidencias/etapa-10b1.md). Trabalho local em feat/iam-prometheus-metrics, sobre 42b9707, incluída neste commit; push pendente.

### Passo a passo da 10B2a — coleta e dashboard do IAM

1. Criar feat/iam-observability-stack sobre 9f5f857, com diretório limpo, e dividir 10B2 em IAM (10B2a) e enforcer/dependências (10B2b).
2. Conferir releases/contratos oficiais, baixar imagens Prometheus/Grafana e fixar versões/digests; preservar a stack de negócio.
3. Criar Compose separado, portas loopback, rede/volumes próprios, healthchecks e limites locais de retenção/memória; não confundir container saudável com scrape up.
4. Criar template de scrape com host.docker.internal/porta IAM e credentials_file de leitura; gerar config/portas/senha Grafana em pasta privada sem sobrescrita/segredos no YAML/argumentos.
5. Provisionar datasource e dashboard IAM de 12 painéis por arquivo, sem edição persistida na UI/acesso anônimo; distinguir zero, indisponível e série ausente.
6. Criar launcher que valida preparação e Compose, inicia com up --wait, sem criar/alterar serviços de negócio. Documentar parada sem remover volumes.
7. Criar smoke opt-in com cópia dos mesmos arquivos/scripts, IAM real em loopback, portas/projeto isolados e infraestrutura descartável; não criar fixture falsa de métricas.
8. Exigir up=1, JVM/buckets, 12 painéis provisionados, parsing de todas as expressões e frame real do datasource; comprovar anonimato 401 e credencial inválida gerando up=0/401, depois recuperar token original.
9. Conferir inicializador sem sobrescrita e artefatos sem credenciais, remover recursos/segredos do teste em finally e registrar limites. Não repetir build completo sem alteração de runtime; registrar validação focalizada separadamente dos 782 testes históricos da 10B1.
10. Atualizar inventário/plano/README/ADR/runbook/evidências e seguir para 10B2b. V22 e grants/ACL preservados; não declarar coleta de enforcer/dependências, QA visual, capacidade ou SLA.

Referências: [operação](runbooks/coleta-iam-local.md), [Compose](../infra/observability/README.md), [ADR-051](adr/051-coleta-e-dashboard-local-do-iam.md), [evidências 10B2a](evidencias/etapa-10b2a.md). Trabalho local em feat/iam-observability-stack, sobre 9f5f857, incluída neste commit; push pendente.

### Passo a passo da 10B2b1 — enforcer

1. Criar feat/enforcer-prometheus-metrics sobre 123c4b0 com diretório limpo; separar exposição do enforcer (10B2b1) de exporters/permissões das dependências (10B2b2).
2. Conferir contrato HttpServer JDK 21/Micrometer, acrescentar registry gerenciado pelo BOM e preservar Spring non-web.
3. Criar listener opt-in, bind fixo loopback, caminho/método exatos, token próprio por arquivo/digest e erros sem segredos; limitar workers/fila e encerrar servidor/executor explicitamente.
4. Testar formato/autenticação/métodos/queries/caminhos, arquivo inválido, parada do listener, modo padrão sem listener e registro JVM real sem servlet.
5. Acrescentar Target Enforcer ao inicializador preservando default IAM e flags de launcher sem ativar consumidor/Vault implicitamente.
6. Criar job/template/overlay opcionais e dashboard separado; preservar fluxo IAM-only. Acrescentar migração com backups para configuração existente sem trocar senha/volumes.
7. Executar módulo/dependência com testes, arquitetura, cobertura e integrações de consumo/Vault; registrar falhas corrigidas sem presumir causas.
8. Criar smoke com JARs/serviços reais: produzir usuário/bloqueio por API e exigir user.locked publicado/processado, identidade LOCKED no Redis e ACK exportado; validar dashboard/expressões/frame Grafana.
9. Provar isolamento de token, up=0/401 só no enforcer e recuperação do original; repetir smoke IAM-only como regressão. Validar gerador/migração em cópias isoladas, sem stack persistente.
10. Registrar resultados/limites, remover apenas recursos de teste e atualizar documentos. V22/grants/ACL preservados; exporters, tracing e capacidade seguem pendentes.

Referências: [operação](runbooks/metricas-enforcer-local.md), [ADR-052](adr/052-metricas-opcionais-do-enforcer.md), [evidências 10B2b1](evidencias/etapa-10b2b1.md), [inventário](OBSERVABILIDADE.md). Trabalho local em feat/enforcer-prometheus-metrics, sobre 123c4b0, incluída neste commit; push pendente.

## 10B2b2a — Coleta RabbitMQ

Objetivo: acrescentar métricas reais do broker à coleta local, preservando o Compose padrão e as credenciais existentes. Dividir 10B2b2 em **a: RabbitMQ**, **b: PostgreSQL**, **c: Redis** e **d: Vault** porque cada serviço exige exporter/configuração/permissões e validação próprios.

1. Criar feat/rabbitmq-prometheus-metrics sobre 7b92e4f. Conferir plugin nativo, porta, agregação e intervalo na documentação primária RabbitMQ.
2. Acrescentar overlay opcional com publicação fixa em loopback e porta externa configurável, mantendo imagem/volume/topologia do broker.
3. Habilitar management e prometheus por arquivo próprio. Conservar configuração base e explicitar /metrics agregado, porta interna 15692 e autenticação desligada somente para desenvolvimento local.
4. Acrescentar template de job RabbitMQ, intervalo 15 s, sem credenciais administrativas, nomes de filas ou payload como labels adicionais.
5. Implementar migração de prometheus.yml com backup, recusa de duplicidade/colisão de coleta e preservação de compose.env/tokens/senha/volumes. O script não executa Docker.
6. Provisionar dashboard separado de seis painéis: disponibilidade de scrape, mensagens prontas/sem ACK, conexões, canais e consumidores.
7. Construir smoke opt-in com cópias dos arquivos versionados, dois projetos Docker isolados e credenciais geradas. Conferir padrão sem porta de métricas e conservação das configurações base.
8. Produzir mensagens AMQP reais em fila exclusiva, manter uma sem ACK, consultar gauges e confirmar redução após ACK; verificar scrape agregado sem nome da fila.
9. Verificar expressões e frame Grafana, recusa de acesso anônimo, pausa/up=0/retomada do broker e artefatos sem segredos. Remover somente containers/volumes privados do teste.
10. Registrar comandos/resultados/limites e atualizar índice/plano/inventário. Manter o smoke RabbitMQ independente de IAM/enforcer; não alegar correlação fim a fim, capacidade, CI ou exporters das demais dependências.

Referências: [operação](runbooks/metricas-rabbitmq-local.md), [ADR-053](adr/053-coleta-opcional-rabbitmq.md), [evidências 10B2b2a](evidencias/etapa-10b2b2a.md), [inventário](OBSERVABILIDADE.md). Trabalho local em feat/rabbitmq-prometheus-metrics sobre 7b92e4f, incluída neste commit; push pendente. Nenhuma alteração da stack persistente do usuário.

## 10B2b2b — Coleta PostgreSQL

Objetivo: acrescentar estatísticas reais do servidor sem fornecer a credencial administrativa ao exporter nem consultar tabelas IAM durante scrape. Depende da coleta 10B2a e sucede RabbitMQ 10B2b2a; Redis/Vault seguem separados.

1. Criar feat/postgres-prometheus-metrics sobre eea6f52. Conferir README 0.19.1, --help da imagem real, digest e papéis PostgreSQL 16.
2. Criar SQL administrativo opt-in fora de Flyway para securegate_metrics: pg_monitor, limite de conexões/timeouts e atributos sem administração, sem grants em tabelas IAM.
3. Recusar atributos/memberships/ownership/ACL inesperados, inclusive SELECT de coluna e CREATE herdado. Não corrigir grants suspeitos silenciosamente. Aplicar criação/configuração/senha em transação.
4. Implementar provisionador com senha CSPRNG em arquivo privado, formato estrito, reuso/rotação explícitos e transmissão ao psql por ambiente temporário; não imprimir valores nem passar senha em argumento.
5. Acrescentar overlay separado com imagem fixada por digest, senha montada read-only, bind que recusa arquivo ausente, porta loopback, filesystem read-only e recursos limitados. O Compose base não inicia exporter.
6. Escolher somente database/stat_database/stat_bgwriter; desabilitar métricas legadas/settings, SQL/tabelas, roles, locks, replication, vacuum e WAL. Não descobrir bancos automaticamente nem exportar payload/query.
7. Acrescentar job a cada 15 s e script de inclusão com backup, recusa de duplicidade/colisão e preservação dos outros jobs/segredos/volumes.
8. Provisionar dashboard de seis painéis distinguindo HTTP up de conexão SQL pg_up, além de conexões, commits, tamanho e deadlocks do cluster.
9. Executar smoke com arquivos versionados, PostgreSQL/exporter/coleta reais descartáveis; verificar ACL com read-only desligado, grants inesperados, arquivo inválido preservado, commits, background writer e seis consultas/frame Grafana.
10. Invalidar credencial SQL e encerrar somente sessões desse login no teste: exigir up=1/pg_up=0; restaurar arquivo/login e exigir recuperação. Revisar artefatos sem segredos, limpar apenas projetos gerados e registrar limites/resultados.

pg_monitor permite leitura ampla de estatísticas/configuração do cluster e pode revelar SQL por interfaces PostgreSQL; os coletores escolhidos não exportam esse texto. PUBLIC permanece existente e o padrão read-only é alterável pelo cliente. Não representa sandbox, capacidade ou correlação distribuída sob carga.

Referências: [operação](runbooks/metricas-postgres-local.md), [ADR-054](adr/054-coleta-opcional-postgresql.md), [evidências 10B2b2b](evidencias/etapa-10b2b2b.md), [inventário](OBSERVABILIDADE.md). Trabalho local em feat/postgres-prometheus-metrics sobre eea6f52, incluída neste commit; push pendente; sem provisionar a stack persistente. V22 e grants IAM/auditoria/PAM preservados.

## 10B2b2c — Coleta Redis

Objetivo: coletar estatísticas Redis sem ler nomes/valores de chaves de sessão/enforcement. Sucede PostgreSQL 10B2b2b; Vault permanece na 10B2b2d.

1. Criar feat/redis-prometheus-metrics sobre 6a48bb6. Conferir versão/digest/flags e código do redis_exporter, mapa de senha e regras ACL oficiais.
2. Selecionar comandos mínimos de monitoração e desabilitar CONFIG, extração de chaves/valores, histogramas de latência e endpoint /scrape de alvos arbitrários. Manter SLOWLOG GET negado, mesmo sendo tentado pelo exporter.
3. Implementar preparador administrativo que consulta ACL ativa com autenticação por ambiente temporário e recusa usuários/default/metrics fora do contrato base. Não normalizar ACL customizada silenciosamente.
4. Gerar par de arquivos privados com senha CSPRNG JSON e ACL com hashes de default/metrics; preservar senha/permissões default e limitar metrics a INFO/PING e metadados sem chaves/canais. Reutilizar somente par válido; recusar arquivos parciais/divergentes.
5. Criar overlay que carrega ACL file no Redis com AOF/volume preservados e acrescenta exporter por digest, senha montada read-only, recursos limitados e porta loopback. Bind não cria arquivos ausentes.
6. Registrar necessidade de reinício/janela, origem da senha default no ACL file, rotação e retirada explícitas. Preparador não altera ACL ativa nem executa reinício/ACL SAVE/CONFIG REWRITE.
7. Adicionar job a cada 15 s, migração com backup/recusa de duplicidade e dashboard de seis painéis. Distinguir HTTP up, redis_up e last_scrape_error.
8. Construir smoke descartável com arquivos versionados e Redis/exporter/coleta reais. Recusar ACL customizada (inclusive usuário com caixa diferente), URI do mapa divergente, arquivo alterado, grants inesperados e comandos de leitura/escrita/scan/admin/logs com credencial metrics.
9. Validar estatísticas agregadas, seis consultas/frame Grafana e scrape sem chave/payload/segredos. Invalidar senha em memória, exigir up=1/redis_up=0, restaurar e confirmar recuperação.
10. Reiniciar Redis e confirmar autenticação metrics/default e dados AOF. Limpar somente projetos privados, revisar artefatos e registrar resultados/limites e próxima entrega.

O smoke pode recarregar a ACL original de sua fixture descartável para desfazer injeção de grant; o preparador do produto não executa ACL LOAD. Regra -get redundante também é recusada pela comparação estrita de comandos. INFO expõe estatísticas operacionais; a ACL não permite acesso a chaves/canais. SLOWLOG GET negado gera ACL LOG esperado e limita métricas de comandos lentos.

Referências: [operação](runbooks/metricas-redis-local.md), [ADR-055](adr/055-coleta-opcional-redis.md), [evidências 10B2b2c](evidencias/etapa-10b2b2c.md), [inventário](OBSERVABILIDADE.md). Trabalho local em feat/redis-prometheus-metrics sobre 6a48bb6, incluída neste commit; push pendente. Nenhuma reconfiguração da stack persistente do usuário, aplicação Java ou migrations.

## 10B2b2d — Coleta autenticada Vault dev

Objetivo: coletar métricas Vault com credencial mínima e autenticação efetiva. Completa contratos individuais das quatro dependências e documenta como correlacioná-los; ensaio conjunto sustentado/capacidade permanecem abertos.

1. Criar feat/vault-prometheus-metrics sobre c55446d. Conferir telemetria/API e código server.go 1.21.4; testar explicitamente acesso anônimo, sem inferir segurança da presença de um token.
2. Registrar comportamento do -dev: primeiro listener libera métricas anônimas. Criar overlay que o restringe a loopback interno e publica listener autenticado na porta existente, com retenção 1 min e hostname desabilitado.
3. Evitar HCL duplicado pelo entrypoint e colisão de portas de cluster. Documentar que aplicar/remover overlay recria Vault dev e perde estado; não executá-lo na stack persistente durante desenvolvimento desta entrega.
4. Criar política read-only em sys/metrics. Implementar provisionador que exige recusa anônima, confere política existente e emite token service orphan de 1h, não renovável e sem default/identidade de aplicação.
5. Guardar token em arquivo privado; preservar token válido, recusar root/outro escopo e permitir -Rotate para válido/revogado/expirado. Instalar atomicamente, revogar anterior por accessor e compensar falha anterior à instalação quando possível.
6. Acrescentar job /v1/sys/metrics?format=prometheus com Bearer por arquivo e overlay somente da coleta. Migrar config/env com backups, flag OBS_VAULT e preservação dos demais jobs/segredos.
7. Provisionar dashboard separado de seis painéis: up, unsealed, requests, barrier gets, token creates e token lookups. Não confundir tokens Vault com JWT OAuth ou contadores com SLA de assinatura.
8. Executar smoke descartável: recusar -dev anônimo antes de emissão; aplicar overlay antes do bootstrap da fixture; verificar token/TTL, KV/Transit/token-management/LIST negados e política/root em arquivo recusados.
9. Validar seis consultas/frame Grafana e ausência de segredos no scrape/artefatos. Injetar arquivo inválido, provar up=0/403 e recuperação; recuperar token revogado e rotacionar válido com revogação antiga, recriando só coletores.
10. Comparar chave Transit, KV e política da aplicação antes/depois da rotação; não reiniciar Vault após criar dados da fixture. Limpar apenas projetos/arquivos privados e registrar resultados, matriz de correlação e limites.

A substituição de arquivo usa NullString explícito no PowerShell 5.1; lookup de token revogado pode responder 403 mesmo para administração. Compensação não torna servidor/disco transacionais; revisar estado parcial em falha. A matriz das quatro dependências está no [runbook](runbooks/metricas-vault-local.md), com testes individuais distintos de execução conjunta sob carga.

Referências: [operação](runbooks/metricas-vault-local.md), [ADR-056](adr/056-coleta-autenticada-vault-dev.md), [evidências 10B2b2d](evidencias/etapa-10b2b2d.md), [inventário](OBSERVABILIDADE.md). Trabalho local em feat/vault-prometheus-metrics sobre c55446d, incluída neste commit; push pendente. Não aplicou reset à stack persistente; Java/migrations e políticas da aplicação preservados.

## Etapa 10B3a — Logs operacionais JSON restritos

Separar serialização de logs (10B3a), tracing HTTP (10B3b) e contexto durável outbox/RabbitMQ (10B3c) permite verificar segurança do formato antes de alterar o transporte. Na conclusão da 10B3a, 10B3b/10B3c eram propostas; as seções seguintes registram a implementação HTTP.

1. Inventariar logs atuais e filtro X-Correlation-ID; preservar respostas, restauração MDC e padrão anterior sem perfil.
2. Conferir StructuredLogFormatter/JsonWriter na versão Boot 3.5.16 e documentação oficial.
3. Definir schema fechado de cinco campos e catálogo de códigos por template conhecido. Omitir texto livre, argumentos, throwable, stack trace, logger/thread e pares SLF4J.
4. Exportar somente SHA-256 de correlationId limitado e validado; vazio se ausente/inválido. Documentar pseudonimização e alta cardinalidade, sem labels Prometheus.
5. Implementar adaptadores nos bootstraps IAM/enforcer sem adicionar Spring/Logback à biblioteca de eventos.
6. Criar perfil opcional structured-logs para console/arquivo, banner desligado, sem habilitar serviços/endpoints.
7. Testar parsing e exclusão de marcadores sintéticos em mensagens, argumentos, exceções, MDC e pares, incluindo CR/LF e limites de identificador.
8. Inicializar contexto Boot mínimo em cada módulo e testar os encoders reais de console/arquivo; encerrar appenders para liberar arquivo temporário no Windows e restaurar logging para os demais testes.
9. Executar regressões de correlação/arquitetura e build completo com gates existentes; registrar resultados reais nas evidências.
10. Documentar ativação, reversão, perda de detalhe e limites. A proteção não abrange stdout/err direto, configurações externas ou logging anterior à inicialização.

[Operação](runbooks/logs-estruturados-local.md), [ADR-057](adr/057-logs-operacionais-json-restritos.md), [evidências](evidencias/etapa-10b3a.md). Branch feat/structured-operational-logs sobre 771487a, incluída neste commit; push pendente. Sem alteração da stack persistente, schema ou protocolo de eventos.

## Etapa 10B3b1 — Tracing HTTP entrante restrito

A 10B3b foi dividida em 10B3b1 (entrada servlet) e 10B3b2 (clientes JDK/Vault), porque a instrumentação Spring não cobre os HttpClients atuais. Na conclusão da 10B3b1, 10B3b2 e 10B3c eram propostas; a seção seguinte registra a implementação da 10B3b2.

1. Inventariar filtro de correlação, métricas, logging e clientes atuais; preservar convenções de métricas e domínio/eventos V22.
2. Adicionar bridge Micrometer/OpenTelemetry gerenciada pelo BOM, sem exporter/agente/backend.
3. Excluir pipelines genéricos e criar provider/tracer/propagator/handler somente sob management.tracing.enabled=true; perfil http-tracing opt-in, padrão false.
4. Definir Resource fixo service.name=iam-core, lifecycle close e amostragem local [0,1], padrão 0,1; parent remoto não impõe sampled.
5. Restringir parser W3C à versão 00/55 caracteres; recusar duplicados e malformados. Não extrair baggage/tracestate/B3.
6. Integrar ao filtro servlet nativo com handler de nome fixo e somente método/classe de status/flag de falha. Descartar attrs da observação, Throwable e eventos.
7. Manter handler de métricas independente do contexto de tracing para que observações Spring Security sem span continuem válidas; preservar long-task timer e labels regulares. Exemplares automáticos OpenMetrics não foram validados por scrape.
8. Registrar EventPublisher como bean para o listener de escopo Boot sincronizar/restaurar MDC. Acrescentar traceId/spanId validados ao JSON IAM (sete campos); enforcer continua com cinco.
9. Testar com IAM/PostgreSQL reais: parent remoto/child, MDC, raiz nova, duplicados, status 500/403 e ausência de dados sintéticos em spans concluídos. Conferir padrão sem tracer e histogramas Prometheus.
10. Executar build completo com integração/arquitetura/cobertura e atualizar operação/evidências com resultados reais; não declarar spans de Vault/AMQP ou tracing fim a fim.

[Operação](runbooks/tracing-http-local.md), [ADR-058](adr/058-tracing-http-entrante-restrito.md), [evidências](evidencias/etapa-10b3b1.md). Branch feat/http-server-tracing sobre b6e2363, incluída neste commit; push pendente; stack persistente e .env preservados.

## Etapa 10B3b2 — Clientes HTTP Vault runtime

1. Inventariar HttpClients de Transit/MFA, banco dinâmico e PAM; separar KV pré-Spring, sem tracer disponível.
2. Criar transporte compartilhado na infraestrutura, sem dependência do bootstrap e sem alterar domínio/eventos/schema.
3. Manter construtores antigos sem tracing e injetar transporte nos beans Spring; resolver dependências opcionais na criação do bean para ordenar sua destruição.
4. Preservar requisição original quando desligado. Quando ligado, copiar método/body/timeouts e remover contexto anterior W3C/B3/baggage.
5. Limitar nova injeção a traceparent, origem configurada e catálogo de paths, sem query/fragment/userinfo e sem redirects habilitados.
6. Criar span CLIENT vault.http como filho corrente ou raiz; não guardar parent no cache de token/sessão/lease.
7. Registrar quatro atributos finitos, sem URL/key/role/lease/SQL/token/body/erro livre; fechar escopo em sucesso/erro, sem retries extras.
8. Testar transporte real HTTP local, default, limites, corpo/credenciais preservados, 403/500/302 e IOException/interrupção.
9. Estender teste IAM real para AppRole → consulta → revogação por JDK, três spans filhos e parent restaurado; Vault HTTP simulado explicitamente.
10. Executar build completo com integrações Vault funcionais existentes, arquitetura e cobertura; registrar horários, avisos e limites, sem afirmar tracing interno de Vault ou async.

[ADR-059](adr/059-tracing-clientes-http-vault.md), [operação](runbooks/tracing-http-local.md), [evidências](evidencias/etapa-10b3b2.md). Branch feat/vault-http-client-tracing sobre d0ef5e2, incluída neste commit; push pendente. Próxima: 10B3c. Stack persistente e .env preservados.

## Etapa 10B3c1 — Contexto durável no outbox

Separar a 10B3c em c1 (captura/persistência/claim), c2 (publicação AMQP) e c3 (consumo) permite validar a transação antes do transporte. Na entrega c1, c2/c3 eram propostas pendentes; a seção seguinte registra a implementação c2.

1. Identificar transação de auditoria/outbox e worker assíncrono; contexto de thread do request não chega ao scheduler.
2. Criar V23 aditiva: trace_parent nullable VARCHAR(55), CHECK W3C/IDs/flags e extensão do trigger imutável; eventos antigos ficam NULL.
3. Preservar JSON/versão v1 de SecurityEvent e construtores antigos dos adapters/claims.
4. Capturar apenas traceparent do span corrente no bootstrap, com Tracer/Propagator opcionais; fornecer Supplier ao adapter sem SDK na aplicação/domínio.
5. Validar em Java e SQL; gravar coluna e envelope no mesmo INSERT/transação. Não usar MDC/correlationId ou mapa de headers para persistência.
6. Carregar metadado no claim; leases/retries/ACK/quarentena não podem reescrevê-lo; omitir contexto no toString.
7. Testar PostgreSQL real: upgrade V22 com evento antigo, rollback audit/outbox, NULL/invalid, constraint/trigger e recusa de UPDATE runtime.
8. Reinstanciar store após retry e comparar parent/payload/identidade, incluindo rejeição de claim antigo; distinguir isso de reinício de JVM.
9. Capturar contexto de requisição HTTP IAM real e verificar o span servidor persistido fora do payload; manter regressões RabbitMQ existentes.
10. Executar build completo, atualizar mapa/rollout/evidências e registrar que V23 precede o novo binário; não migrar stack persistente nem declarar AMQP/consumidores implementados.

[ADR-060](adr/060-contexto-duravel-no-outbox.md), [operação](runbooks/tracing-outbox-local.md), [evidências](evidencias/etapa-10b3c1.md). Branch feat/outbox-trace-context sobre 32f3bfa, incluída neste commit; push pendente. Próxima: 10B3c2.

## Etapa 10B3c2 — Tracing da publicação AMQP

1. Revisar claim/confirm/retry e manter OutboxPublisher sem dependências do SDK; instrumentar somente o adapter RabbitEventTransport.
2. Conectar Tracer opcional no bootstrap, reutilizando http-tracing; preservar construtor antigo e publicador desabilitado por padrão.
3. Validar parent durável novamente; construir parent explícito com traceId/spanId/sampled. Usar raiz explícita quando ausente/inválido, sem herdar worker/MDC/correlationId.
4. Criar span outbox.publish PRODUCER para cada tentativa; sampler local determina flags, mesmo diante de parent sampled.
5. Serializar somente traceparent W3C 00 a partir do filho produtor e validar. Criar mapa novo com schemaVersion e, quando ativo, traceparent; não copiar baggage/tracestate/B3/maps externos.
6. Preservar body UTF-8, messageId/correlationId, persistência, routing e confirm mandatory. Encerrar escopo/span em finally; não alterar lease/backoff/ACK.
7. Limitar atributos a messaging.system=rabbitmq e messaging.outcome finito; não registrar exceções livres/payload/identidades/host do broker.
8. Testar SDK real com canal simulado: tracing desligado, parent durável versus worker, retries com filhos diferentes, NULL/contexto hostil, sampler zero e fechamento em falhas.
9. Testar banco/broker reais: captura audit/outbox, confirm antes de UPDATE, lease expirada e store/transport reinstanciados; verificar redelivery idempotente em identidade/body, parent imutável e header do novo produtor. Simular queda sem alegar reinício real de JVM.
10. Executar seleção e verify completo; atualizar decisões, guia operacional, inventário e evidências, preservando a pendência de consumo 10B3c3 e de exporter/backend/desempenho.

[ADR-061](adr/061-tracing-publicacao-amqp.md), [operação](runbooks/tracing-outbox-local.md), [evidências](evidencias/etapa-10b3c2.md). Branch feat/outbox-trace-context sobre 57f34bf; incluída neste commit; push pendente. Sem nova migração; V23 segue obrigatória e stack persistente não foi migrada.

## Etapa 10B3c3a — Tracing do consumo no enforcer

1. Dividir a proposta c3 em c3a (enforcer individual) e c3b (auditoria IAM em lote); preservar atomicidade do lote e não escolher parent arbitrário entre mensagens.
2. Adicionar bridge Micrometer/OTel ao enforcer; provider explícito opcional, resource fixo, sampler local e perfil consumer-tracing. Excluir autoconfigurações genéricas; manter runtime non-web e workers opt-in separados.
3. Extrair somente traceparent String/LongString W3C 00 com tamanho/IDs/flags restritos; descartar tipos/formato/chaves ambíguas sem mudar validação de negócio.
4. Criar security.consume CONSUMER por entrega, com parent explícito ou raiz independente; ignorar baggage/tracestate/B3, MDC/correlationId e contexto anterior do worker.
5. Envolver decoder, handler, efeito Redis e ACK/REJECT/NACK no mesmo escopo; preservar catch/desconexão/requeue/backoff/deduplicação/TTL existentes.
6. Restaurar escopo e os dois campos MDC em finally. Não guardar parent entre ticks; retry/redelivery gera novo span filho.
7. Limitar quatro atributos finitos: sistema, consumidor, redelivery e outcome. Acrescentar traceId/spanId validados ao JSON enforcer (sete campos), mantendo exclusão de texto/argumentos/exceções livres.
8. Testar SDK real: default, String/LongString, parents hostis, raiz versus worker, sampler zero, outcomes e exceções/MDC. Verificar schema e perfis reais de logs/configuração.
9. Testar RabbitMQ/Redis reais: revogação retry/recovery com filhos distintos e poison → legacy na fila de políticas sem vazamento de parent. Verificar contexto Spring default e opt-in com tracer.
10. Executar seleção e verify completo; documentar limites, reversão, próximos passos e ausência de nova migração/alteração da stack. Não declarar auditoria IAM ou cadeia completa implementadas.

[ADR-062](adr/062-tracing-consumo-enforcer.md), [operação](runbooks/tracing-consumidores-local.md), [evidências](evidencias/etapa-10b3c3a.md). Branch feat/outbox-trace-context sobre be06e27, incluída neste commit; push pendente. Próxima: c3b, preservando lote/isolamento/ACK da auditoria.

## Etapa 10B3c3b — Tracing da auditoria IAM em lote

1. Conferir estado inicial: 10B3c3a já estava no commit 103553e, sem alterações pendentes; não criar commit vazio nem push ao iniciar esta entrega.
2. Identificar appendBatch atômico e parents distintos; escolher span raiz com links, sem dividir transação ou atribuir arbitrariamente o primeiro parent.
3. Criar AuditConsumerTrace na infraestrutura; conectar Tracer opcional do IAM no bootstrap e preservar construtor anterior/default sem inspeção de headers.
4. Extrair somente String/LongString traceparent W3C estrito por OutboxTraceParent; rejeitar parent ambíguo/hostil/ausente para NULL, preservando decisão de negócio.
5. Guardar apenas parent validado/redelivery no Pending, com toString restrito; deduplicar links por traceId/spanId, no máximo o limite de 100 mensagens do lote.
6. Após decode puro, criar audit.consume.batch CONSUMER raiz explícita. Envolver appendBatch, fallback e disposição; sampler local e links sem atributos arbitrários.
7. Rejeição inválida recebe span de disposição; fallback isolado recebe span com parent próprio. Restaurar lote/escopo/MDC em finally e manter commit/ACK/poison/late/retry/desconexão anteriores.
8. Limitar cinco atributos finitos e excluir payload, erro livre, identidade ou métricas novas. Não persistir contexto de consumo no banco/envelope v1.
9. Testar SDK/canal simulado para links/parents/MDC/ACK incerto e banco/broker reais para lote misto, appendBatch único, retry/redelivery e deduplicação.
10. Executar seleção e verify completo; registrar resultados/limites e atualizar decisões/operação/inventário. Concluir instrumentação c3 sem declarar aceite global 10B: backend/coleta conjunta seguem propostos na 10B4.

[ADR-063](adr/063-tracing-auditoria-em-lote.md), [operação](runbooks/tracing-consumidores-local.md), [evidências](evidencias/etapa-10b3c3b.md). Branch feat/outbox-trace-context sobre 103553e; incluída neste commit; push pendente. Sem nova migração ou alteração da stack persistente.

## Próximas divisões 10–12 — propostas, ainda pendentes

As metas e o escopo principal continuam no plano. As divisões abaixo são uma proposta de sequência, sujeita a ajuste documentado conforme os resultados. A 10B4 explicita backend/exportação e coleta conjunta, já pendentes no aceite global 10B; não representa uma entrega realizada nem renumera as entregas anteriores.

| Proposta | Passos previstos | Saída verificável |
| --- | --- | --- |
| 10B4 — Fechamento da observabilidade | Definir backend/exportação/retenção/acesso → integrar spans/links restritos → validar coleta conjunta e falha/recuperação → registrar aceite. | Observabilidade integrada verificável antes da medição 10C; proposta ainda não implementada. |
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
| V21 | 9C2a | Agenda persistida e metadados de retry da limpeza PAM. |
| V22 | 9C2b | Agenda persistida e metadados de retry da observação de pedidos PAM. |
| V23 | 10B3c1 | Parent de tracing opcional e imutável no outbox, separado do envelope v1. |

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
