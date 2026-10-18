# Evidências — Etapa 05, entrega 5B2

- Data: 18/10/2026, America/Sao_Paulo.
- Branch: `feat/trusted-risk-signals`, baseada no commit local `738c660` da 5B1.
- Commit: este registro acompanha o commit da entrega 5B2, consultável com `git log -- docs/evidencias/etapa-05b2.md`. Push, PR e CI remota desta versão ainda pendentes.
- Resultado: coleta local de contexto autenticado, navegador aprovado, conexão classificada, falhas reais e perfil de recurso. MFA e aplicação das decisões permanecem na 5C/5D.

## Ambiente e execução

Windows, JDK 21.0.8, Maven Wrapper 3.9.11, Spring Boot 3.5.16. PostgreSQL 16.15 e Vault 1.21.4 em Testcontainers com dados sintéticos. Sem dependência externa nova. V7 cria tabelas de evidência/configuração com FKs e índices.

```powershell
.\mvnw.cmd -B -ntp spotless:apply
# Em cópia temporária isolada dos mesmos arquivos versionáveis:
.\mvnw.cmd -B -ntp clean verify
```

Log em `.firecrawl/etapa5b2-isolated-verify.log`, cópia isolada indicada em `.firecrawl/etapa5b2-validation-path.txt` e relatórios em `.firecrawl/etapa5b2-reports/`. O isolamento evita a interferência da IDE em target observada nas entregas anteriores; artefatos ignorados pelo Git.

**BUILD SUCCESS: 329 testes, zero falhas, erros ou ignorados**, em aproximadamente 87 segundos. São 18 casos adicionais sobre os 311 da 5B1: onze de validação de rede no domínio, dois do histórico JDBC com relógio controlado, três de navegador HTTP e dois de administração/coleta com PostgreSQL real. Formatação, arquitetura e limites JaCoCo aprovados.

SHA-256 dos fontes/scripts/configurações conferido contra a cópia validada, incluindo ausência do adaptador removido. Relatórios copiados para a pasta local e JAR validado para `iam-core/target/iam-core-0.1.0-SNAPSHOT.jar`. Links locais revisados, exemplos PowerShell do guia analisados pelo parser e `git diff --check` aprovado. Busca dos segredos reais de `.env` e dos arquivos AppRole nos arquivos versionáveis não encontrou exposição.

## Aceite observado

- Login real cria contexto ligado ao usuário/versão/sid e cookie HttpOnly de navegador; GETs autenticados renovam observação. API administrativa lista somente metadados dos contextos, omitindo IP.
- Headers Forwarded/X-Forwarded-For forjados não alteram a origem gravada. Faixa CIDR explícita de loopback classifica a conexão direta; nenhuma rede recebe confiança por padrão.
- Aprovação administrativa versionada muda o próximo risco; expiração e revogação removem a confiança. Versão antiga retorna 409 e dispositivo de outro usuário retorna 404.
- Copiar o cookie de um usuário para login de outro gera um identificador novo sem aprovação. Contexto de outro usuário/sem ID não fornece dispositivo/rede; somente selecionar um ID não renova a evidência.
- Expiração de contexto, mudança da versão da identidade e fechamento retiram confiança. Contexto não pode ser reatribuído nem reaberto, inclusive sob a mesma versão. Logout fecha a linha; reautenticação fecha o contexto anterior e gera outro, preservando somente o navegador lembrado da mesma conta.
- Falhas reais de senha para conta conhecida são contadas por UUID e permanecem após login válido; usuário desconhecido mantém a mesma resposta genérica. Após a janela, o contador volta a zero. Testes de relógio fixo cobrem aquecimento, perda de cobertura após falha de escrita e recuperação somente após outra janela completa.
- Cadastro/atualização de sensibilidade e rede, normalização de CIDR e conflitos testados por HTTP. Desativar a faixa altera imediatamente a próxima coleta. Falha de auditoria desfaz a alteração da classificação.
- Literais IPv4/IPv6 e prefixos são validados; hostname, endereço abreviado/fora dos limites, zona e prefixo inválido são rejeitados sem resolução DNS.
- Sensibilidade é coletada independentemente do contexto; sem Authorization, comportamento segue desconhecido. Contador não completo não é interpretado como zero. A avaliação continua simulation=false; cenários sintéticos permanecem separados.
- Migrações V1–V7 e atualização de banco V5 com política existente preservam dados legados. Testes anteriores de login/PKCE/OIDC, rotação/reuso, política/ABAC e falha do Vault permanecem no build completo.

## Limites e pendências

Coleta corresponde à sessão/nó local; não se declara saúde coordenada de produtores em múltiplas instâncias. Cookie representa navegador lembrado, sem atestação de hardware ou fator MFA. Rede corresponde ao par conectado ao IAM; modelo de proxies confiáveis não foi implementado. Retenção/expurgo periódico de evidência ainda precisa de operação na etapa 8. Banco/backups contêm IP observado e exigem proteção.

Decisões permanecem recomendações administrativas. A emissão de JWT não passou a aplicar políticas de risco automaticamente. Não foram iniciados servidores Java persistentes, alterados `.env` ou aplicadas migrações ao Compose persistente. V7 será aplicada ao banco local na próxima inicialização.

A base publicada até a 5A mantém a [CI aprovada](https://github.com/PauloHFA/SecureGate-IAM/actions/runs/39159901831); 5B1 e 5B2 ainda aguardam push/CI remota. Revisão/merge pendentes.

Referências: [ADR-012](../adr/012-coleta-confiavel-de-risco.md), [guia operacional](../runbooks/coleta-risco-local.md) e [roteiro](../PLANO-DE-IMPLEMENTACAO.md).
