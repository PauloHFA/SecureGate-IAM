# Evidências — 6A1: envelope e Outbox transacional

- Data: 2026-10-19.
- Branch: `feat/security-event-outbox`, base `7713a94` (5D3).
- Escopo: envelope compartilhado v1 e persistência atômica da Outbox para eventos já auditados.
- Commit/publicação/CI remota/revisão: pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15 e Vault 1.21.4 temporários: **416 testes, zero falhas, zero erros e zero ignorados**. Duração do build final: aproximadamente 1 minuto e 41 segundos. Spotless, arquitetura e cobertura JaCoCo mínima de 80% por pacote de domínio/aplicação do `iam-core` aprovados.

O build rodou em cópia isolada para evitar interferência da IDE em `target`. Os 234 arquivos relevantes de código/configuração/scripts, incluindo os contratos JSON, foram comparados por SHA-256 com as fontes validadas. JAR copiado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa6a1-verify.log`; relatórios em `.firecrawl/etapa6a1-reports/`, ignorados pelo Git.

A entrega acrescenta dez casos de contrato no módulo compartilhado e cinco testes de integração transacional. O teste de atualização de banco V5 foi ampliado para preservar a política/auditoria existentes e verificar ausência de backfill. As expectativas de migrações foram atualizadas para V9. A primeira execução encontrou somente a expectativa antiga de três migrações após V5; a execução final, com quatro, passou.

## Casos verificados

- Envelope com nove campos, UUID, tipo, versão 1 e instante UTC; serialização/deserialização sem Spring, inclusive para as seis chaves de integração previstas e um tipo legado.
- Campos obrigatórios/versão do artefato JSON conferidos; não foi usado motor completo de validação JSON Schema. Construtor Java valida nomes, versão, cabeçalhos, limites, horário e metadados.
- Mapas imutáveis e independentes da entrada; `toString()` omite contexto/dados.
- Criação de usuário por HTTP confirma estado, credencial, auditoria e Outbox; envelope tem o mesmo ID/horário da auditoria, metadados esperados e não contém a senha.
- Falha de append desfaz usuário/auditoria/Outbox. A FK das credenciais impede persistência sem o usuário confirmado.
- Auditoria chamada sem transação externa ainda grava o par atomicamente; falha não deixa auditoria órfã. Append direto sem transação é recusado.
- Outra conexão não vê o evento não confirmado; rollback externo remove o par, e commit posterior torna a pendência visível.
- ID duplicado, alteração de payload e cabeçalho JSON null são recusados no banco.
- V5 atualiza até V9 preservando dados antigos, sem criar Outbox retroativa; Flyway valida e não reaplica migrações já concluídas.
- Regressão dos 70 testes HTTP de navegador, OAuth/OIDC, refresh, políticas/risco/MFA, recursos e administração preservada.

Documentação, links locais, exemplos PowerShell e `git diff --check` revisados. Comparação com os valores de credenciais locais não encontrou correspondências nos arquivos alterados. As anotações de triagem GitGuardian do trabalho anterior foram preservadas; o estado do incidente externo não foi alterado.

## Aceite e limites

Concluída a **6A1**, sem declarar a 6A completa. Todo evento novo gravado pelo adaptador `JdbcAuditTrail` recebe uma Outbox na mesma transação. Não há backfill, publicador, alteração de RabbitMQ, confirmação/retry, consumidor Redis ou medição de propagação distribuída nesta entrega.

As seis chaves de segurança previstas são aceitas pelo envelope, mas seus dados e emissores reais ainda precisam ser integrados na **6A2**. Tipos/dados atuais de auditoria foram preservados. Eventos aguardam publicação com `published_at` nulo. A 6B publicará no broker e a 6C definirá operação/limpeza/recuperação.

Nenhuma credencial ou infraestrutura persistente foi alterada. A V9 foi validada nos bancos temporários; o ambiente local receberá a migração ao iniciar o novo JAR. Nenhum servidor persistente foi iniciado para esse aceite.

Referências: [ADR-018](../adr/018-envelope-e-outbox-transacional.md), [guia](../runbooks/outbox-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).
