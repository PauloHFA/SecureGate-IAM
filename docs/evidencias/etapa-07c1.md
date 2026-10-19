# Evidências — 7C1: enforcement Redis no recurso

- Data: 2026-10-19.
- Branch: `feat/resource-redis-enforcement`, base `5bd4c8f` (7B).
- Escopo: leitura opcional de revogação/identidade/bloqueios/política no recurso HTTP, estados estritos e indisponibilidade.
- Commit, publicação e CI remota: pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **514 testes, zero falhas, zero erros e zero ignorados**. Build final concluído em 3 minutos e 25 segundos, às 20:03:24 de 2026-10-19 (UTC−03). Spotless, regras de arquitetura e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços.

A entrega acrescenta quatro testes unitários e seis casos HTTP na suíte de navegador/recurso. O teste de compatibilidade de chaves acompanha a regressão real dos escritores 7A/7B. O teste de projeção antiga usa identidade com versão local maior que zero para exercitar esse ramo explicitamente.

Build executado em cópia isolada para evitar interferência da IDE em `target`. Os 292 arquivos relevantes de código/configuração/scripts coincidem por SHA-256 com as fontes validadas. JARs preservados em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar` e `policy-enforcer/target/policy-enforcer-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa7c1-verify.log`; relatórios em `.firecrawl/etapa7c1-reports/`, ignorados pelo Git.

Links locais, exemplos/script PowerShell e `git diff --check` conferidos. Não houve correspondência entre credenciais locais e conteúdo dos arquivos alterados. Nenhuma stack persistente foi interrompida pelos testes.

## Casos cobertos

- Chaves de projeção compartilhadas e opacas, com namespaces separados, sem expor sujeitos. Os escritores 7A/7B usam o mesmo gerador, preservando o protocolo existente.
- Porta adicional só roda depois da validação local do token/grant; o authorizationId consultado é o persistido. Negação ou falha não produz Subject/Permit.
- Serviço da aplicação diferencia impedimento de requisição, watermark de política e indisponibilidade. Modo desabilitado conserva os checks locais.
- Configuração exige senha no modo ativo, limita timeout e omite credencial do diagnóstico. Script de inicialização controla a ativação por ambiente.
- Access token real, assinado pelo Vault e válido no grant local, passa a receber 401 com marca jti, sid ou família. Remoção da marca de teste permite nova autorização local.
- Identidade LOCKED na mesma versão ou watermark mais novo nega o token; ACTIVE na versão corrente permite continuar. Uma versão local maior que zero permite conferir que a projeção antiga não sobrepõe a fonte local fresca.
- Bloqueio conta/versão ou IP do par real responde 429, com Retry-After e sem desafio WWW-Authenticate. Forwarded/X-Forwarded-For não contornam IP bloqueado. Expiração real da chave permite nova tentativa.
- Política projetada mais nova ou disabled na mesma versão responde 403. Projeção enabled não supera DENY local.
- Valor Redis malformado e chave do tipo lista onde se espera string respondem 503 sem detalhe interno. Corrigir o estado de teste recupera a operação.
- Pausa real do Redis provoca timeout e 503; recuperação restaura resposta 200 sem alterar token ou reiniciar o IAM.
- Regressão de assinatura/audiência, escopos, TTL, MFA, auditoria e grant/identidade locais permanece coberta; a suíte de navegador/recurso passou de 77 para 83 casos.

Os testes novos usam PostgreSQL, Vault e Redis temporários e aplicam estados de projeção diretamente no Redis. Não comprovam nesta entrega a cadeia completa de publicação/consumo ou latência abaixo de dois segundos. Os testes 7A/7B de escritores, incluindo RabbitMQ/Redis reais, permanecem no build completo.

## Aceite e limites

A 7C foi subdividida. A **7C1** entrega o recurso com leituras opcionais, falha fechada e rate limiting por bloqueio já projetado. A **7C2** ainda integrará login/emissão/refresh e os sinais de risco nos fluxos apropriados. A **7D** medirá propagação ponta a ponta.

A conexão Redis é criada apenas no modo ativo, com timeout limitado. Ausência de chave não concede acesso; banco/política/MFA locais continuam obrigatórios. Não há fallback permissivo quando a leitura falha, cache de decisões Redis, incremento de tentativas por GET ou reconstrução após perda de dados.

O switch novo ativa Authorization/Transit e usa as credenciais existentes; não inicia o enforcer ou publicador por conta própria. Nenhuma stack persistente, credencial, topologia ou migração SQL foi alterada. A leitura entre grupos Redis e o banco não é um snapshot transacional global; atualizações concorrentes podem ser observadas na requisição seguinte.

Referências: [ADR-024](../adr/024-consulta-redis-no-recurso.md), [guia](../runbooks/recurso-enforcement-redis-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).
