# Evidências — Etapa 5C2: cadastro MFA, recuperação e step-up

- Data: 2026-10-18.
- Branch: `feat/mfa-enrollment-step-up`.
- Escopo: cadastro e ativação TOTP, recuperação de uso único e prova autoritativa por sessão.

## Validação

`mvnw.cmd -B -ntp clean verify` aprovado em cópia isolada das fontes, com JDK 21 e PostgreSQL 16.15/Vault 1.21.4 temporários: **357 testes, zero falhas, zero erros e zero ignorados**. Duração aproximada: 1 minuto e 32 segundos. Spotless, regras de arquitetura e limite JaCoCo de 80% por pacote de domínio/aplicação aprovados.

Os 207 arquivos de código/configuração/scripts relevantes foram comparados por SHA-256 com a cópia validada, evitando interferência do build automático da IDE em `target`. O JAR validado foi preservado em `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`. Log em `.firecrawl/etapa5c2-verify.log`; relatórios em `.firecrawl/etapa5c2-reports/`, ignorados pelo Git.

Links locais e exemplos PowerShell da documentação validados. Os 48 testes HTTP de navegador incluem os oito casos MFA desta entrega, com acesso negado ao token bootstrap e encerramento concorrente da sessão.

## Casos cobertos

- Login recente e senha atual exigidos para iniciar; fator ativo não é substituído por senha.
- Formulários autenticados e CSRF; token bootstrap não concede sessão MFA. Semente e lista de recuperação não reaparecem em GET.
- Semente cifrada no Vault e persistida como ciphertext; recuperação com dez códigos aleatórios, persistidos somente como hashes.
- Confirmação exige sid e versão originais, prazo do cadastro e código válido; código de ativação já consumido.
- Falhas persistidas e compartilhadas entre sessões; quinta falha bloqueia a conta para novas tentativas MFA por cinco minutos.
- Duas verificações simultâneas do mesmo código TOTP ou de recuperação têm somente um sucesso.
- Auditoria indisponível desfaz ativação, consumo e prova. Mensagens e detalhes de auditoria não carregam sementes/códigos.
- Prova expira em cinco minutos e exige usuário, sid, identidade atual/ativa e fator ativo; logout/reautenticação/alteração de identidade invalidam seu uso.
- Encerramento concorrente mantém marcador fechado e impede uma requisição em andamento de recriar prova válida; uma tentativa posterior no sid fechado é rejeitada sem consumir outro código.
- Erro do Vault retorna 503 sem consumir código/contador nem penalizar como falha de código.
- Migrações V1–V8 e atualização a partir de V5; regressões de OAuth/OIDC, Identity/Access, políticas e risco.

## Limites

Login/tokens ainda não aplicam políticas/MFA automaticamente; isso continua na 5D. Prova é vinculada à sessão, sem vínculo adicional à operação nesta versão. Cadastro é opcional. Remoção, substituição do fator ativo e regeneração de códigos não fazem parte desta entrega. Não há recuperação por e-mail nem bypass administrativo.

Não foram aplicadas migrações ao Compose persistente nem alteradas credenciais locais. V8 será aplicada na próxima inicialização do novo JAR. Custódia durável do Vault é pré-requisito para fatores reais. Publicação/CI remota, revisão e merge continuam pendentes.

Referências: [ADR-014](../adr/014-cadastro-mfa-recuperacao-e-step-up.md), [guia](../runbooks/mfa-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
