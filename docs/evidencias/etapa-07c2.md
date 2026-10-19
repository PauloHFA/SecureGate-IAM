# Evidências — 7C2: login e emissão com enforcement Redis

- Data: 2026-10-19.
- Branch: `feat/authentication-redis-enforcement`, base `45926c0` (7C1).
- Escopo: login, navegação de autorização e emissão/refresh humanos com sinais distribuídos e MFA real por risco.
- Entrega local; publicação e CI remota pendentes.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado com Java 21, PostgreSQL 16.15, RabbitMQ 4.3.6, Redis 7.4.11 e Vault 1.21.4 temporários: **527 testes, zero falhas, zero erros e zero ignorados**. Build concluído em 3 minutos e 18 segundos, às 20:23:08 de 2026-10-19 (UTC−03). Spotless, regras de arquitetura e cobertura mínima JaCoCo de 80% dos pacotes configurados aprovados nos dois serviços.

O build usa cópia isolada para evitar interferência da IDE em `target`. Os 295 arquivos relevantes de código/configuração/scripts coincidem por SHA-256 com as fontes validadas. JARs preservados em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar` e `policy-enforcer/target/policy-enforcer-0.1.0-SNAPSHOT.jar`; log em `.firecrawl/etapa7c2-verify.log`; relatórios em `.firecrawl/etapa7c2-reports/`, ignorados pelo Git.

## Casos cobertos

- IP bloqueado responde 429 uniformemente antes da consulta de credenciais; CSRF continua obrigatório e nenhuma falha fictícia de senha é registrada pelo filtro.
- Bloqueio de conta/versão e identidade projetada recusam login de forma genérica, sem sessão autenticada. Estado Redis malformado responde 503 sem registrar falha de senha; corrigido o estado, login volta a funcionar.
- Sid encerrado, família revogada, conta/IP bloqueados e identidade recusada impedem refresh. Removida a causa de teste, o mesmo refresh ainda pode ser usado, preservando a rotação posterior.
- Watermark mais novo é reavaliado no refresh; projeção não substitui a decisão de política local.
- Risco por sid exige prova MFA real na sessão original. Após cadastro e verificação reais, o refresh volta a funcionar e o token expira até o prazo da prova, sem ampliar escopos ou alterar o score local.
- Pausa real do Redis impede login e refresh com 503; recuperação permite nova tentativa sem reiniciar IAM ou perder refresh.
- Negação da troca de Authorization Code faz rollback: o mesmo código válido pode ser trocado depois de removida a causa.
- Client Credentials conserva seu fluxo; o modo desabilitado preserva a composição anterior. Falhas de dependência não produzem Permit ou prova MFA.
- Regressão de recursos, assinatura/audiência, escopos, TTL, identidade, grant, MFA e auditoria permanece no build completo.

Os testes acrescentam seis casos unitários e sete HTTP. As projeções dos testes HTTP são aplicadas diretamente em Redis descartável; os testes dos escritores 7A/7B permanecem no build. A cadeia completa Outbox → RabbitMQ → enforcer → HTTP e sua latência ainda não são comprovadas nesta entrega.

## Aceite e limites

A 7C2 implementa o escopo funcional local. A etapa 7 aguarda a **7D**, com testes integrados e medição da propagação. Não se declara latência abaixo de dois segundos.

O rate limiting aplica bloqueios produzidos pelos contadores assíncronos da 7B; não é token bucket síncrono nem limite rígido de QPS. Introspecção/userinfo mantêm verificações locais. Perda/eviction Redis e reconstrução de estado continuam exigindo tratamento operacional; leituras não formam snapshot global com banco e enforcer. Timeouts são por comando.

O switch existente `-EnableResourceEnforcement` ativa o novo comportamento. Não há novo segredo, migração, dependência ou topologia. A stack persistente não foi interrompida pelos testes. Links locais, exemplos PowerShell e `git diff --check` conferidos; nenhuma correspondência com credenciais locais nos arquivos alterados.

Referências: [ADR-025](../adr/025-enforcement-login-emissao.md), [guia](../runbooks/autenticacao-enforcement-redis-local.md) e [plano](../PLANO-DE-IMPLEMENTACAO.md).