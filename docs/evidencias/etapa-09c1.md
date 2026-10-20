# Evidências — Etapa 9C1

- Data: 20/10/2026, horários UTC−03.
- Branch: feat/pam-operational-audit.
- Base versionada: 9B1 feaf718. A 9B2 e a consolidação documental já estavam locais sem commit e foram preservadas; esta validação inclui esse conjunto e a 9C1.
- Commit local: esta entrega reúne 9B2, consolidação documental e 9C1. Publicação/CI remota: pendentes.

## Entrega

V20 aditiva, correlações de origem e marcadores separados de expiração em pedidos/emissões. Auditoria/Outbox e marcador condicionais na mesma transação, sem duplicar transição após repetição/concorrência. Fato de prazo lógico independente da confirmação de revogação. Códigos fixos para falhas/recusas, com ator verificado e sem inferência a partir de token inválido. Job de pedido também disponível sem emissão PAM habilitada.

## Seleção focada

```powershell
.\mvnw.cmd -B -ntp spotless:apply
.\mvnw.cmd -B -ntp -pl iam-core -am '-Dtest=PamAuditTest,PamCredentialServiceTest,PrivilegedRequestServiceTest,ResourceAccessServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=BrowserAuthorizationIT#pam*,RuntimeDatabasePrivilegesIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

Aprovada às 15:08:35, duração Maven de 1 min e 4 s: **83 casos**, 41 unitários e 42 integrações (oito cenários PAM HTTP e 34 de permissões runtime). Zero falhas, erros ou ignorados. Log ignorado: `.firecrawl/etapa9c1-focused-final.log`.

Comprova expiração lógica com Vault pausado sem registrar revogação inexistente; marcador volta a null quando a auditoria falha; tentativa posterior grava uma única expiração e conserva a correlação da emissão. Observadores de pedido concorrentes também geram uma única transição. Recusas de política/MFA têm ator verificado e códigos fixos; falha de auditoria recusa a operação com indisponibilidade. Token inválido não é usado como identidade de um fato PAM. Concessões permitem marcadores e negam alterações da origem.

## Build completo

```powershell
.\mvnw.cmd -B -ntp clean verify
```

**BUILD SUCCESS** às 15:17:53, duração Maven de **8 min e 40 s**. Foram aprovados **734 testes**, consolidados de 67 relatórios XML Surefire/Failsafe, com zero falhas, erros ou ignorados. Os oito testes de ponta a ponta passaram; verificações de arquitetura, formatação e limites de cobertura também foram aprovadas. Log ignorado: `.firecrawl/etapa9c1-verify.log`.

Revisão complementar: 827 links Markdown locais válidos, os 40 registros de evidências de etapas referenciados pelo guia e `git diff --check` sem erros. A comparação dos arquivos alterados com sete valores sensíveis locais não encontrou correspondências; nenhum desses valores foi registrado no log ou nos documentos.

## Limites

Sem SLA de observação, auditoria de toda requisição anônima/corpo inválido, retenção operacional ou correção de perda do Vault dev. As duas correlações de origem são referências, não identidades/chaves de deduplicação; linhas antigas usam UUID do pedido como fallback. A 9C2/9C3 e KV do enforcer permanecem pendentes. Não foi alterada a stack persistente nem foram usados segredos locais nos containers de teste.

Referências: [ADR-040](../adr/040-auditoria-operacional-pam.md), [guia](../runbooks/auditoria-pam-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
