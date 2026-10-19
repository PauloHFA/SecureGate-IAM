# Evidências — 7B: consumidor de bloqueio e política

- Data: 2026-10-19.
- Branch: `feat/enforcer-block-policy-consumer`, base `3a0c348` (7A).
- Escopo: projeções versionadas de identidade/política, contadores de limiar e sinais temporários.
- Commit, publicação e CI remota: pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **504 testes, zero falhas, zero erros e zero ignorados**. Build final concluído em 2 minutos e 52 segundos, às 13:38:58 de 2026-10-19 (UTC−03). Spotless, arquitetura e cobertura mínima JaCoCo de 80% dos pacotes configurados de domínio/aplicação aprovados nos dois serviços.

A entrega acrescenta quatro casos parametrizados do contrato de identidade, quatro testes unitários do enforcer e oito cenários reais na suíte de consumo, que passou de 10 para 18 casos. O módulo isolado passou antes da regressão completa. O build usa cópia isolada para evitar interferência da IDE em `target`; os 285 arquivos relevantes de código/configuração/scripts, incluindo Lua, coincidem por SHA-256 com as fontes validadas.

JARs preservados em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar` e `policy-enforcer/target/policy-enforcer-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa7b-verify.log`; relatórios em `.firecrawl/etapa7b-reports/`, ignorados pelo Git. Links locais, exemplos PowerShell e `git diff --check` conferidos. Não houve correspondência entre credenciais locais e conteúdo dos arquivos alterados.

## Casos cobertos

- Quatro tipos administrativos de identidade exigem status fechado, versão válida e ausência de campos pessoais extras; fixture de round-trip atualizada para o contrato.
- Domínio mapeia identidade, bloqueio administrativo, política, falha de login e risco, sem inventar conta para tentativas desconhecidas. Cabeçalhos inválidos não chamam persistência.
- Consumidor só confirma aplicação/duplicata; conflito rejeita à DLQ e erro ou retorno ausente do store exige retry.
- Versões 9007199254740992 e 9007199254740993 são distinguidas no Redis real. LOCKED antigo não substitui ACTIVE novo e o watermark não expira. Mesma versão com status contraditório é rejeitada.
- Política desabilitada mais nova não é revertida por mensagem antiga; conteúdo contraditório na mesma versão vai à DLQ.
- Mesmo eventId e mesmo attemptId em outro evento não duplicam contagem. Conjuntos de tentativas permanecem limitados aos limiares de teste; conta/versão nova não herda bloqueio da versão antiga.
- Conta desconhecida alimenta apenas IP observado. Três representações equivalentes de IPv6 alimentam o mesmo contador canônico.
- Bloqueios/sinais realmente expiram e replay atrasado não os ressuscita. Login suspeito não gera bloqueio automático de seu IP.
- Tipo Redis inválido no segundo contador é detectado antes de alterar o primeiro, deixa mensagem em requeue e não grava conclusão; reparo permite aplicar uma vez.
- Efeito já aplicado antes do ACK aceita duplicatas concorrentes de dois workers. Outro corpo com o mesmo eventId é encaminhado à DLQ sem contar o segundo alvo.
- Sinais mais de 60 segundos no futuro e tipos futuros de identidade são rejeitados sem efeito.
- O contexto Spring opt-in inicia ambos os workers, aplica revogação/identidade e fecha seus recursos. Desabilitação padrão continua coberta.

A suíte mantém os testes 7A de timeout real do Redis, reinício real do aplicativo RabbitMQ, redelivery, TTL e recuperação. Os cenários novos de falha de projeção usam tipo Redis incompatível, sem simular queda física adicional dos serviços. O caso entre efeito e ACK grava o efeito e republica o corpo; não encerra o processo à força nesse intervalo.

## Aceite e limites

A 7B projeta estado e limiares, sem consulta pelo IAM nesta entrega. Política é watermark de invalidação, não documento completo nem decisão ALLOW. Rate limiting no caminho HTTP e comportamento com Redis indisponível continuam na 7C; medição ponta a ponta, na 7D.

Watermarks não expiram; sinais e bloqueios usam prazos a partir do evento. Contadores são limitados por sujeito, não uma contagem total para auditoria; o total de sujeitos e marcadores exige acompanhamento de memória. Valores de limiar são parâmetros locais iniciais. Redis perdido/evictado não é reconstruído integralmente por esta entrega.

Nenhuma stack persistente ou credencial foi alterada. Não há nova migração SQL ou topologia. O script existente de inicialização agora habilita os dois consumidores, usando as credenciais locais existentes. Publicação/CI remota e revisão continuam pendentes.

Referências: [ADR-023](../adr/023-consumidor-bloqueio-politica.md), [guia](../runbooks/enforcer-bloqueio-politica-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).
