# Evidências — Etapa 9A3

- Data: 20/10/2026; horários UTC−03.
- Branch: feat/enforcer-vault-kv; base a9b67f0 (9C3).
- Estado: implementação local, incluída neste commit; push/CI remota pendentes.

## Entrega e comandos

Postprocessor registrado no enforcer, KV v2 fixo com AppRole por arquivo, três propriedades permitidas, snapshot prioritário/atômico, revogação do token e falha fechada. Launcher com -EnableVaultKv combinável com -EnableConsumer. Sem alteração de ACL ou migração: V22 permanece atual.

```powershell
.\mvnw.cmd -B -ntp -pl policy-enforcer -am spotless:apply verify
.\mvnw.cmd -B -ntp clean verify
```

A primeira validação encontrou um erro de serialização de Instant no produtor do novo teste; foi corrigido usando o ObjectMapper do contexto Spring. O teste real então comprovou descoberta do postprocessor, prioridade sobre argumentos com credenciais inválidas, conexão Redis e consumo RabbitMQ com projeção de revogação e ACK. A ACL sem permissão foi rejeitada pelo Vault real sem snapshot/fallback.

Validação focada: BUILD SUCCESS às 21:50:58, duração Maven 46,907 s, cobertura aprovada. O build completo terminou com BUILD SUCCESS às **22:01:30**, duração Maven **10 min e 22 s**. Foram aprovados **778 testes em 72 relatórios XML**, sem falhas, erros ou ignorados. Inclui os **28 testes unitários e 2 de integração novos** do KV do enforcer e os **11 cenários E2E** anteriores, todos aprovados. Arquitetura, Spotless e limites JaCoCo IAM/enforcer passaram. O processo de shell reportou status 1 ao redirecionar avisos nativos em stderr; a conclusão Maven BUILD SUCCESS e os relatórios Surefire/Failsafe foram conferidos separadamente, com zero falhas/erros. Logs locais ignorados em .firecrawl/etapa9a3-*.log.

## Revisões complementares

Parser PowerShell sem erros. Execução do launcher com comando java substituído por função de verificação confirmou modo anterior, modo KV sem credenciais locais, endereço loopback e restauração das 13 variáveis de processo; nenhuma JVM foi iniciada nessa verificação.

922 links Markdown locais válidos e 44 registros de evidências de etapas referenciados no guia. git diff --check sem erros. Comparação em memória dos 13 arquivos alterados com quatro valores sensíveis locais: zero correspondências, sem imprimir os valores. Nenhum segredo real foi usado como fixture ou registrado na documentação.

## Limites e próximo trabalho

Testes usam infraestrutura descartável, senhas geradas e arquivos temporários; stack persistente intacta. O teste sobe contexto Spring real, enquanto os 11 cenários E2E existentes usam JARs. Não há refresh de KV, rotação automática ou teste de produção. O snapshot não é uma prova de saúde de Redis/RabbitMQ e a queda posterior do Vault não remove valores em memória. Recriar Vault dev não recupera chaves perdidas. A conta do broker local continua a de bootstrap compartilhado.

Falta revisar controles/aceite global da etapa 9 e publicar/validar CI; depois iniciar 10A. Entrega geral/hardening de produção continua na etapa 11.

Referências: [ADR-044](../adr/044-bootstrap-kv-enforcer.md), [operação](../runbooks/enforcer-vault-kv-local.md), [guia](../GUIA-DE-IMPLEMENTACAO.md).
