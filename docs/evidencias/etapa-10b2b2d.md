# Evidências — 10B2b2d: coleta autenticada Vault dev

- Data: 21/10/2026, America/Sao_Paulo.
- Branch: feat/vault-prometheus-metrics, base c55446d.
- Estado: implementação e validação locais concluídas; incluída neste commit; push/CI remota pendentes.
- Escopo: listener publicado autenticado, política read-only, token limitado/rotação, job/overlay e dashboard de seis painéis.

## Descobertas e ajustes

Probe nativo confirmou Prometheus em Vault 1.21.4, mas a primeira checagem só usava root. O smoke anônimo recebeu 200, contrariando a hipótese de autenticação do modo dev. Conferido server.go da versão: -dev força métricas anônimas no primeiro listener. Corrigido com overlay que move esse listener para loopback interno e publica listener autenticado; o provisionador recusa a configuração insegura antes de emitir token.

O entrypoint da imagem já carrega /vault/config; passar o mesmo HCL via -config duplicou listeners. Removida a duplicação e explicitada porta de cluster distinta. Após isso, testes de política/TTL, recusas de KV/Transit/tokens e coleta/Grafana passaram até a rotação. File.Replace com $null no PowerShell 5.1 gerou caminho inválido; corrigido com NullString explícito e conferido em arquivos temporários isolados. Lookup administrativo de token revogado respondeu 403 (confirmado em probe isolado), não apenas 400: recuperação explícita passou a admitir ambos, preservando validação de escopo para tokens encontrados. Não aceitar tentativas intermediárias como validação concluída.

Validação funcional após os ajustes:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=VaultObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 14:25:02 UTC−03**, duração **1 min e 51 s**, **1 teste** (104,3 s), zero falhas/erros/ignorados; exit code nativo 0. Log ignorado .firecrawl/etapa10b2b2d-smoke.log. Spotless/compilação/enforcer de ambiente aprovados; suíte padrão e JaCoCo não executados nessa seleção.

Seleção final de regressão do launcher com seus overlays:

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=VaultObservabilitySmoke,ObservabilitySmoke,EnforcerObservabilitySmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' verify
```

**BUILD SUCCESS às 14:29:43 UTC−03**, duração **3 min e 58 s**, **3 testes**, zero falhas/erros/ignorados; exit code nativo 0. Vault: 93,26 s; IAM: 61,83 s; enforcer: 75,72 s. Log ignorado .firecrawl/etapa10b2b2d-final.log. As seleções se sobrepõem: não somar reexecuções como testes distintos.

Checks finais: três scripts com parser PowerShell aprovado, JSON com seis painéis/seis expressões, 1.303 links locais válidos e 57 arquivos de evidências referenciados no guia. Os 25 arquivos alterados foram comparados em memória com cinco valores privados de .env, sem ocorrências; sem whitespace final e git diff --check aprovado. Projetos/probes descartáveis encerrados. Não foi repetida a suíte padrão/arquitetura/cobertura completa; não há Java de produção alterado.

| Contrato | Verificação |
| --- | --- |
| Baseline real | Vault base 1.21.4 devolve 200 anônimo; provisionador recusa antes de emitir token/arquivo. |
| Listener protegido | Overlay aplicado só à fixture ainda sem dados; endpoint publicado passa a exigir token e anônimo recebe 403. |
| Bootstrap real | Script versionado cria KV, Transit e AppRoles da aplicação depois do startup protegido; sem endpoints falsos. |
| Escopo/TTL | Service orphan, apenas securegate-metrics, máximo 3600 s, não renovável; reexecução conserva token válido. |
| ACL efetiva | KV, Transit sign, gerenciamento de tokens, lookup-self, leitura de política da aplicação e LIST metrics retornam 403. |
| Estado inesperado | Política ampla existente e root colocado no arquivo são recusados; arquivos/política não corrigidos silenciosamente. |
| Migração | Colisão/duplicidade recusadas, dois backups iguais ao original e senha Grafana preservada; flag OBS_VAULT ativa o bind da coleta. |
| Coleta/Grafana | Seis expressões com métricas nativas reais, frame via datasource e acesso anônimo Grafana 401. |
| Falha/recuperação | Arquivo de token inválido leva a up=0/403; restaurar original recupera up=1. |
| Revogado | Sem Rotate, preserva arquivo de token revogado e recusa; com Rotate, cria credencial válida. |
| Rotação válida | Instala token novo e revoga anterior; HTTP antigo 403/novo 200; Prometheus recriado lê novo bind e target volta a up. |
| Preservação | Sem reiniciar Vault após bootstrap: chave pública Transit, KV canário e política IAM iguais antes/depois de provisionar/rotacionar. Não comprova preservação durante reset do dev. |
| Artefatos | Logs/config fora dos privados comparados com tokens novos/antigos/root, AppRole SecretIDs, senha Grafana/Basic e canário, sem ocorrências. |
| Limpeza | Remover somente projetos gerados/volumes e credenciais de teste em finally; não coletar logs brutos do Vault dev com root/unseal. |

 Nenhum reset/reinício/provisionamento foi aplicado à stack persistente do usuário. Apenas projetos descartáveis e arquivos ignorados foram usados; .env do usuário não foi alterado.

## Limites e próximo passo

Aplicar overlay em Vault dev existente perde estado em memória; bootstrap posterior não restaura chaves antigas ou todo o estado de Database/PAM. O smoke testa preservação durante rotação após startup protegido, não durante a aplicação do overlay. Token de 1h sem renovação automática; rotação opera em Vault e disco sem transação distribuída, com compensação limitada/documentada. Listener/HTTP local sem TLS; sem alterações de Java de produção, migrations ou políticas/grants da aplicação.

Matriz de correlação das quatro dependências documentada; não foi comprovada execução conjunta sustentada ou capacidade/SLA. Suíte padrão/arquitetura/cobertura completas não são comprovadas por seleção opt-in. Próxima: 10B3 — logs/tracing; depois 10C–10D — medição/ajustes.

[ADR-056](../adr/056-coleta-autenticada-vault-dev.md), [operação](../runbooks/metricas-vault-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md), [inventário](../OBSERVABILIDADE.md).
