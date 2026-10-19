# ADR-024 — Consulta das projeções no recurso protegido

- Estado: aceita para implementação local na 7C1.
- Data: 2026-10-19.
- Base: [revogação](022-consumidor-de-revogacao.md) e [bloqueio/política](023-consumidor-bloqueio-politica.md).

## Escopo e composição

A 7C foi dividida: **7C1** consulta as projeções no recurso `GET /api/resources/account`; **7C2** integrará login/emissão/refresh e os sinais de risco nos fluxos apropriados. A medição da cadeia completa permanece na 7D.

A integração é opcional, desabilitada por padrão e ativada com `-EnableResourceEnforcement`. O switch ativa Authorization/Transit no processo, usa as credenciais Redis existentes e não publica eventos nem inicia consumidores por conta própria. O consumidor continua em processo separado.

`ResourceEnforcement` é uma porta da aplicação de autorização. O adaptador lê Redis; bootstrap compõe o modo ativo ou desabilitado. O recurso conserva assinatura, issuer, audiência, grant exato no PostgreSQL, ledger, identidade atual, escopos, política viva, TTL e MFA. Redis acrescenta sinais de negação; ausência de chave não concede acesso nem substitui essas verificações. Client Credentials continua sem acesso ao recurso humano.

## Ordem e respostas

1. Depois da validação local do token/grant, consultar jti, sid e authorizationId da autorização persistida. Marca presente e válida nega com 401. O authorizationId da consulta vem do armazenamento confiável, não de parâmetro do chamador.
2. Consultar versão/status de identidade. Versão Redis maior que a do sujeito, ou mesma versão LOCKED/DISABLED, nega com 401. Projeção mais antiga não sobrepõe identidade mais recente validada no banco.
3. Depois de validar identidade/tempo do sujeito, consultar bloqueio de conta/versão e IP do par conectado. Marca presente nega com 429 `rate_limited` e Retry-After de cinco segundos. Forwarded/X-Forwarded-For não definem o IP. Não existe fallback que omita IP quando a observação não puder ser validada.
4. Após decisão local positiva de política/escopos/TTL, conferir os watermarks das políticas aplicadas. Versão Redis maior ou mesma versão disabled impede o permit, com 403. Projeção mais antiga não substitui política viva mais nova. A ausência de watermark não concede autorização; DENY/MFA/escopo locais continuam obrigatórios. O digest do alvo é validado como formato, sem reconstruir documentos ou decisões a partir dele.
5. Concluir prazo/MFA e auditoria existentes antes de responder ao recurso.

Erro de conexão/comando/script, tipo Redis incompatível, valor malformado ou versão fora do intervalo resulta em 503 `temporarily_unavailable`, Retry-After de cinco segundos e sem conteúdo interno. Não há cache permissivo ou fallback para acesso bem-sucedido. Token inválido pode ser recusado localmente com 401 sem consultar Redis. Requisição já negada por política não precisa provar disponibilidade de outra dependência para continuar negada.

## Protocolo e validação

`SecurityProjectionKeys`, no módulo compartilhado sem dependência de Redis, centraliza namespaces/hash slots e SHA-256 dos sujeitos para leitores e escritores. Mantém os bytes do protocolo 7A/7B; não exige limpar Redis ou migrar chaves. O módulo IAM não depende do executável `policy-enforcer`.

Consultas agrupadas usam script GET sem mutações por hash slot. GET recusa tipo incompatível; MGET não foi usado porque pode representar tipo incompatível como ausência. Valores ausentes são diferenciados de resposta inválida. Revogações e identidade são lidas em grupos separados; não existe snapshot transacional global entre slots, banco e requisição. Uma revogação concorrente depois da leitura pode ser observada na requisição seguinte.

Marcas temporárias usam presença e expiração do Redis como autoridade; não se recalcula TTL no relógio da aplicação. Valores de deadline devem ser inteiros positivos até o limite de ano 9999; `0` só é aceito para sid permanente. Watermarks seguem os formatos da 7B, com versão de 19 dígitos convertida a Java long sem arredondamento. Por consulta, são aceitas até 512 políticas aplicadas; excesso falha fechado.

## Operação

A conexão Lettuce dedicada usa senha obrigatória no modo ativo, timeouts de conexão/comando de 500 ms por padrão (100–2000 ms), rejeita comandos quando desconectada e não registra credenciais. Esse timeout é por comando/conexão, não um orçamento total de latência da requisição.

Autoconfiguração genérica de Redis é desabilitada no IAM para evitar criar conexões/health de Redis quando o recurso está desligado. No modo ativo, o factory dedicado permite o health padrão Redis no agregado; a readiness existente permanece baseada em disponibilidade da aplicação e banco. Isso não demonstra prontidão de enforcement: o recurso responde 503 quando sua leitura falha, enquanto outros fluxos locais ainda podem funcionar nesta fase.

Contadores `iam.resources.enforcement` usam tags fechadas `operation=token|request|policy` e `result=allowed|denied|unavailable`, sem token, identificador ou IP. Allowed é somente o resultado daquela leitura adicional, não comprovação de autorização final.

## Limites

A 7C1 aplica bloqueios já projetados no recurso, sem incrementar contadores por chamada e sem limitar login/emissão nesta entrega. Redis risco por sid ainda não altera a decisão local de risco/MFA. Não há reconstrução do estado após perda/eviction: a ausência de marcas não comprova histórico completo. O banco continua sendo consultado e esta entrega não transforma o recurso em verificador offline ou prova propagação abaixo de dois segundos.

Não há novo endpoint administrativo, segredo, topologia ou migração SQL. TLS, credenciais de leitura separadas e alta disponibilidade permanecem posteriores. Referências: [guia](../runbooks/recurso-enforcement-redis-local.md) e [evidências 7C1](../evidencias/etapa-07c1.md).
