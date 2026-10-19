# ADR-012 — Coleta de sinais de risco

- Data: 18/10/2026
- Estado: aceito para a entrega 5B2

## Contexto

A 5B1 calcula risco e avalia ABAC, mas usa sinais ausentes na avaliação normal. Para substituir essa ausência, cada fato precisa de origem autorizada, vínculo e prazo. O endereço e o dispositivo do administrador que avalia outro usuário não representam o contexto desse usuário.

## Fontes implementadas

| Sinal | Origem e vínculo |
| --- | --- |
| Identidade e papéis | Estado/versão de Identity e atribuições diretas/herdadas de Access, como nas entregas anteriores |
| Dispositivo | Identificador aleatório de navegador, emitido após login e ligado ao usuário; aprovação administrativa versionada com prazo |
| Rede | Endereço remoto observado na conexão de uma sessão autenticada, classificado por faixas CIDR administradas no servidor |
| Comportamento | Falhas reais do formulário de login associadas internamente ao UUID de conta existente, na janela de cinco minutos |
| Recurso | Perfil de sensibilidade cadastrado administrativamente por identificador exato de recurso |

Não há endpoint público de ingestão de fatos. A avaliação recebe somente um contextId opcional; IP, dispositivo, aprovação, contador e sensibilidade não são confiados a partir de seu JSON. O endpoint de cenários permanece separado, com simulation=true, sem persistência como evidência.

## Contexto de navegador

Na autenticação bem-sucedida e em GETs da cadeia de navegador, o produtor interno observa usuário, versão, sid opaco, identificador de navegador e conexão. O sid já gerado no servidor é o ID do contexto; não armazenar JSESSIONID em claims ou nessas tabelas. A gravação exige identidade ACTIVE com versão atual. A linha não pode ser reatribuída a outro usuário/versão/dispositivo, nem reaberta após fechamento.

O coletor só usa um contexto quando ID e usuário correspondem, versão coincide com a identidade atual, ele não está fechado e observedAt está entre agora−300s e agora+5s. Sem ID, ou com ID desconhecido/de outro usuário/expirado, dispositivo e rede ficam UNKNOWN. Contador e sensibilidade continuam sendo coletados por usuário/recurso independentemente do contexto. O produtor pode renovar observedAt somente em uma requisição autenticada; apresentar o ID em evaluate não renova evidência.

Logout fecha o contexto além de encerrar a sessão local. Novo login fecha o contexto anterior antes de gerar outro, usando o usuário anteriormente guardado na sessão mesmo se a conta for trocada. Mudança de senha/perfil/estado faz a versão antiga deixar de corresponder. Isso não acrescenta revogação distribuída de JWT, que permanece na etapa 7.

## Navegador lembrado

Cookie sg_device tem UUID aleatório emitido pelo servidor, HttpOnly, SameSite=Lax, path /, duração de 30 dias e Secure quando a conexão ao IAM usa TLS. Não possui Domain. Hints desconhecidos ou pertencentes a outro usuário são substituídos por novo identificador sem aprovação. A sessão guarda a escolha feita no login, impedindo que trocar o cookie no meio da sessão troque a identidade do dispositivo observado.

Novo navegador é UNTRUSTED. Aprovação exige bootstrap administrativo, usuário ACTIVE e versão do registro, com TTL de 60–86400 segundos. Revogação torna trusted_until nulo. Cada coleta consulta o prazo atual, sem cache de confiança; expiração/revogação produzem UNTRUSTED para um contexto ainda válido. Aprovação de outro usuário não é reaproveitada. O sinal representa um navegador lembrado e aprovado, sem atestado de hardware, MDM, WebAuthn ou comprovação de MFA.

## Rede e recurso

Faixas aceitam apenas literais numéricos IPv4/IPv6 com prefixo válido, sem resolução de nomes ou expressões. PostgreSQL normaliza host bits para CIDR; faixas equivalentes conflitam. Pelo menos uma faixa habilitada correspondente torna a origem TRUSTED; caso contrário, origem observada válida é UNTRUSTED. Ausência de endereço utilizável é UNKNOWN. Nenhuma faixa é confiável automaticamente, inclusive loopback.

Configurar forward-headers-strategy=none: Forwarded e X-Forwarded-For não reescrevem a conexão observada. A classificação representa o par conectado ao IAM. Se houver proxy, esse par é o proxy; classificação do usuário final exige uma futura configuração explícita de proxies confiáveis. Não interpretar adicionar o proxy à allowlist como validação do IP final.

Recursos têm classificação PUBLIC, INTERNAL, CONFIDENTIAL ou RESTRICTED, com versão otimista. Sem perfil, sensibilidade é UNKNOWN. Perfil não é uma medição efêmera: é configuração administrativa atual, lida em cada avaliação. Redes habilitadas/desabilitadas e perfis atualizados afetam a coleta seguinte.

## Histórico de falhas

O handler registra somente UUID, ID de evento e instante do servidor. Usa a mesma normalização de e-mail do login para encontrar a conta; desconhecidos não geram registro. Senha, e-mail informado, IP e erro detalhado não são armazenados nessa tabela. A resposta continua genérica para credenciais incorretas, desconhecidos e estados não habilitados.

O contador filtra agora−300s ≤ occurredAt ≤ agora e satura em 1000, respeitando o contrato do motor. Não limpar falhas quando houver login válido. Para não declarar um zero com cobertura incompleta, iniciar com cinco minutos de aquecimento por processo. Com Authorization desabilitado, contador permanece desconhecido. Falha na gravação mantém a resposta genérica, registra somente o tipo da falha e reinicia a janela de cobertura conservadora; consultas com banco indisponível interrompem avaliação, sem fallback seguro.

A cobertura é local ao produtor nesta versão de sessões locais. Coordenação de saúde dos produtores em múltiplas instâncias e monitoramento distribuído ficam para as etapas operacionais. Após reinício, o aquecimento volta a valer mesmo com histórico persistido.

## Persistência, administração e limites

V7 cria login_failures em Identity e browser_devices, browser_contexts, resource_profiles e trusted_networks em Policy. Há FKs de usuário/dispositivo e índices para janela/contexto. Os contextos guardam IP observado e exigem proteção de dados do banco/backups. Listagem administrativa de contextos omite o IP e devolve no máximo vinte contextos atuais; redes lista no máximo cem registros. Retenção/expurgo periódico dessas tabelas ainda precisa ser integrado à operação na etapa 8; não se declara expurgo automático.

Cadastros/edições de redes, recursos e aprovações usam bootstrap, concorrência otimista, no-store e auditoria na mesma transação. Falha de auditoria desfaz a alteração. Auditoria inclui versão e tipo da mudança, sem cookie, IP ou documento de cenário; o recurso é identificado por UUID estável na auditoria. Não há publicação RabbitMQ nesta entrega.

Os sinais passam a alimentar evaluate, mas decisões continuam recomendações administrativas. Não negar login, modificar grants ou reduzir tokens automaticamente aqui. A 5C implementará MFA; a 5D aplicará as decisões. Coleta observacional não equivale a atestação forte de endpoint, reputação externa ou detecção comportamental calibrada.

Referências: [ADR-011](011-abac-e-risco-deterministico.md), [guia de coleta](../runbooks/coleta-risco-local.md), [evidências](../evidencias/etapa-05b2.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
