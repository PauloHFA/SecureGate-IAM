# Evidências — Etapa 5C1: TOTP e cifra Transit

- Data: 2026-10-18.
- Escopo: algoritmo TOTP, geração de sementes e proteção autenticada pelo Vault, como primeira entrega da 5C.
- Branch: `feat/mfa-totp-foundation`.

## Validação

`mvnw.cmd -B -ntp clean verify` executado em cópia isolada das fontes, com JDK 21, PostgreSQL 16.15 e Vault 1.21.4 temporários: **343 testes, zero falhas, zero erros e zero ignorados**. Spotless, arquitetura e limite de cobertura JaCoCo aprovados. Duração aproximada: 1 minuto e 31 segundos.

Os 199 arquivos de código/configuração/scripts relevantes foram comparados por SHA-256 com as fontes do build. A cópia isolada evita a interferência do build automático da IDE em `target`. O JAR validado foi copiado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`. Log em `.firecrawl/etapa5c1-verify.log`; relatórios em `.firecrawl/etapa5c1-reports/`, sem versionar credenciais.

O bootstrap atualizado foi executado separadamente em outro Vault temporário: criação AES, reaplicação preservando a versão 1 e rejeição de chave existente exportável aprovadas. A sintaxe dos scripts PowerShell e do shell foi validada, assim como os links locais da documentação. O smoke test completo da stack Compose não foi executado nesta entrega.

## Casos cobertos

- Seis vetores oficiais SHA1 do RFC 6238 no perfil de seis dígitos, incluindo zeros iniciais e ano 2603; semente decodificada conforme o vetor publicado.
- Janelas atual/anterior/posterior, início Unix, códigos fora da janela, contador já aceito e contexto temporal inválido. Códigos malformados/Unicode são rejeitados.
- Sementes canônicas de 160 bits e representação de diagnóstico redigida.
- Round trip com Vault real e AppRole limitado pela política do repositório; cifras diferentes para a mesma semente, sem chave local.
- Vínculo AAD ao usuário e à inscrição, rejeição de adulteração e rejeição de plaintext decifrado que não seja semente válida.
- Rotação da chave AES mantendo a leitura de ciphertext antigo; chave MFA ausente do JWKS RSA e não exportável/sem backup em texto puro.
- Políticas negam rotação/exportação/exclusão ao `iam-core` e cifra/decifra ao `policy-enforcer`.
- Bootstrap idempotente cria a chave AES uma única vez e interrompe quando uma configuração existente permite exportação.
- Respostas malformadas, negação remota, timeout, concorrência limitada e falha sem fallback quando Vault para; mensagens não propagam causas ou respostas remotas.
- Transit desabilitado não cria o protetor MFA. Regressão de OAuth/OIDC, Identity/Access, políticas e coleta de risco no build completo.

## Limites

Esta entrega não contém cadastro de fator, persistência, recuperação, contador consumido transacionalmente, bloqueio de tentativas, auditoria MFA ou prova de sessão. Não se declara MFA satisfeito nem a etapa 5C concluída. Essas capacidades compõem a 5C2; aplicação das decisões continua na 5D.

O Compose persistente não precisa ser alterado para rodar os testes de integração. A mudança do bootstrap/política será aplicada nesse ambiente quando `vault-init` for reexecutado. Vault dev perde chaves ao reiniciar e não serve de custódia durável para fatores reais.

Publicação, CI remota e revisão/merge desta entrega ainda pendentes. Referências: [ADR-013](../adr/013-base-totp-e-cifra-transit.md), [guia](../runbooks/mfa-base-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
