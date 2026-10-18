# Evidências — Etapa 03

- Data: 18/10/2026, America/Sao_Paulo.
- Branch local: `feat/identity-access`, baseada no commit `84cdd44` da etapa 2.
- Commit: este registro acompanha o commit da etapa 3, consultável com `git log -- docs/evidencias/etapa-03.md`. Push e PR ainda pendentes.
- Resultado: implementação e validação local de Identity/Access concluídas. A CI remota desta versão ainda não foi executada.

## Entrega por partes

- **3A:** usuários, normalização de e-mail, credencial separada, Argon2id, perfil, estado, senha e controle de versão.
- **3B:** catálogos de papéis/grupos/permissões, concessões/revogações e permissões efetivas diretas/herdadas.
- **3C:** bootstrap Bearer desabilitado por padrão e auditoria na mesma transação das alterações.
- **3D:** testes de domínio, aplicação, arquitetura, HTTP/PostgreSQL, concorrência, rollback e limite de cobertura no build.

Decisões: [ADR-004](../adr/004-identity-access-bootstrap.md). Operação e exemplos: [provisionamento local](../runbooks/provisionamento-local.md).

## Ambiente e execução

Windows 11, Oracle JDK 21.0.8, Maven Wrapper 3.9.11 e Docker Engine com containers Linux. Mantidos Boot 3.5.16 e Testcontainers 1.21.4. Adicionados Bouncy Castle 1.86 e JaCoCo 0.8.15 com versões centralizadas.

```powershell
.\mvnw.cmd -B -ntp spotless:apply clean verify
```

O reactor completo terminou com `BUILD SUCCESS`. O comando limpou os artefatos anteriores, formatou e verificou os fontes, compilou os módulos, executou Surefire/Failsafe e verificou a cobertura por pacote.

| Grupo | Casos | Falhas / erros / ignorados |
| --- | --- | --- |
| Contratos de eventos | 20 | 0 / 0 / 0 |
| Domínio Identity e Access | 30 | 0 / 0 / 0 |
| Casos de uso Identity e Access | 21 | 0 / 0 / 0 |
| Arquitetura e fixtures de violação | 9 | 0 / 0 / 0 |
| Configuração/filtro bootstrap | 4 | 0 / 0 / 0 |
| Correlação HTTP/MDC | 3 | 0 / 0 / 0 |
| Integração HTTP e PostgreSQL | 19 | 0 / 0 / 0 |
| **Total** | **106** | **0 / 0 / 0** |

Os 19 testes de integração incluem os 8 testes de bootstrap da aplicação da etapa 2 e 11 testes administrativos novos. Cada classe usa PostgreSQL 16.15 temporário em porta aleatória; as credenciais de teste são independentes do `.env` e os dados do banco de desenvolvimento não foram alterados.

## Cobertura

JaCoCo exige **pelo menos 80% de linhas por pacote** selecionado de domínio/aplicação de Identity e Access. O check faz parte de `verify` e falha quando o limite não é atingido.

| Pacote | Linhas cobertas / total | Cobertura |
| --- | --- | --- |
| `identity.domain` | 32 / 32 | 100% |
| `identity.application` | 59 / 59 | 100% |
| `access.domain` | 26 / 26 | 100% |
| `access.application` | 74 / 74 | 100% |

Esses percentuais correspondem aos pacotes medidos, não à cobertura de todo o projeto nem à cobertura de branches. Portas sem código executável não acrescentam linhas.

## Comportamentos verificados

- Migrações V1 e V2 aplicadas e validadas em PostgreSQL real.
- Usuário criado com e-mail normalizado, estado ACTIVE, versão 0 e senha somente na tabela separada como hash Argon2id.
- Prefixo/parametrização Argon2id conferidos, senha validada por `matches` e troca produzindo novo hash.
- Perfil, bloqueio, desbloqueio, troca de senha e desativação incrementam versão; DISABLED não pode ser reativado nesta versão.
- Credenciais não aparecem nas respostas; senha e token sintéticos não aparecem nos logs capturados do fluxo.
- Bootstrap desligado por padrão bloqueia os endpoints administrativos. Quando ligado, chamadas sem token, com token inválido ou header duplicado são rejeitadas antes dos controllers.
- Consulta a usuário existente ou inexistente sem credencial recebe a mesma falha de autenticação, sem divulgar o e-mail.
- Nomes/entradas inválidas, páginas fora dos limites e UUID malformado retornam 400; unicidade/versão retornam 409; recurso autenticado ausente retorna 404.
- Catálogos e relações são persistidos; concessão repetida não duplica relação nem auditoria.
- Papéis diretos e de grupo fornecem permissões sem duplicações; revogações removem o acesso correspondente.
- Usuários LOCKED/DISABLED têm permissões efetivas vazias e não recebem novas concessões; revogações continuam disponíveis.
- Dois cadastros concorrentes do mesmo e-mail produzem um sucesso e um conflito, com uma credencial e um evento de criação.
- Duas atualizações concorrentes na mesma versão produzem um sucesso e um conflito, sem perder alterações nem duplicar auditoria.
- O repositório também rejeita escrita com versão antiga quando chamado diretamente.
- Falha de auditoria reverte cadastro/credencial e concessão de permissão na transação.
- Auditoria registra ator e correlação, sem senha/hash/e-mail; UPDATE, DELETE e TRUNCATE são rejeitados pelos triggers.
- Regras ArchUnit continuam passando com os novos domínios, casos de uso e adaptadores reais.

## Scripts e CI

`Initialize-Local.ps1` foi executado para acrescentar o token bootstrap ao `.env` existente. A administração permaneceu desabilitada. Os scripts preservam credenciais existentes e não imprimem o token. O script de execução recebeu `-EnableBootstrap`, mantendo o modo padrão fechado.

O workflow inclui o relatório JaCoCo no artefato de testes. Sua sintaxe foi validada com actionlint 1.7.12. A execução no GitHub da etapa 3 depende de commit/push/PR; a CI verde registrada na etapa 2 não representa validação remota deste código novo.

## Artefatos e limites

- Log local: `.firecrawl/etapa3-clean-verify.log`.
- Relatórios unitários: `libs/security-events/target/surefire-reports/` e `iam-core/target/surefire-reports/`.
- Relatórios de integração: `iam-core/target/failsafe-reports/`.
- Cobertura HTML/XML/CSV: `iam-core/target/site/jacoco/`.
- A inicialização HTTP com bootstrap ativo/inativo foi validada pelos testes Spring Boot, sem iniciar outro processo persistente da aplicação.
- Auditoria ainda é síncrona e não particionada; Outbox/mensageria e isolamento completo de privilégios ficam para suas etapas previstas.
- O bootstrap é temporário; ainda não há login de usuário, OAuth/OIDC, JWT ou MFA.

Próximo passo: revisar/publicar a etapa 3 e seguir para OAuth/OIDC com assinatura Transit na etapa 4.
