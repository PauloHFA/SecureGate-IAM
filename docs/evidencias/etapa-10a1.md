# Evidências — Etapa 10A1: base de carga e Client Credentials

- Data: 20/10/2026; horários UTC−03.
- Base: 255fdff (9D); branch feat/load-client-credentials-smoke.
- Estado: implementação local, incluída neste commit; publicação/CI a verificar separadamente.

## Entrega

Gerador PowerShell de clientes pela API administrativa, CSV privado em diretórios ignorados, JMX com dois contratos separados, perfil de gravação sem dados sensíveis e runner com dashboard/contagem/asserções. Teste opt-in JMeterSmoke chama scripts/plano reais contra JARs e serviços descartáveis. Não há alteração de aplicação, banco, grants, provisionadores ou stack persistente. V22 permanece atual.

A 10A foi dividida em 10A1 (esta base máquina) e 10A2 (massa humana e jornadas restantes). Observabilidade/medição/ajustes continuam em 10B–10D. O smoke não fecha a 10A nem o aceite de desempenho.

## Ambiente e comandos executados

Java 21, PowerShell Windows, Docker Desktop 29.9.1, serviços PostgreSQL 16.15-alpine, Redis 7.4.11-alpine, RabbitMQ 4.3.6-management-alpine e Vault 1.21.4. JMeter **5.6.3** baixado do arquivo oficial Apache, conferido pelo SHA-512 publicado e extraído em .firecrawl/tools, ignorado. Nenhuma instalação/binário foi versionado. O download foi explícito nesta sessão; o build não o automatiza.

```powershell
.\mvnw.cmd -B -ntp -pl integration-tests -am spotless:apply '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify
.\mvnw.cmd -B -ntp -pl integration-tests -am '-Dtest=NoSelectedUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=JMeterSmoke' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djacoco.skip=true' '-Dsecuregate.jmeter.home=C:/Projetos/SecureGate IAM/.firecrawl/tools/apache-jmeter-5.6.3' verify
.\mvnw.cmd -B -ntp clean verify
```

## Smoke opt-in

Primeira execução aprovada às **22:50:13**, duração Maven **51,437 s**. Após restringir destino de credenciais e acrescentar metadados do ambiente, seleção final aprovada às **22:51:31**, duração **48,551 s**, com **1 teste**, zero falhas/erros/ignorados. JaCoCo foi desativado nessa seleção focada; ela não substitui os gates do build padrão.

| Execução | token.success | token.invalid_client.expected | Resultado do runner |
| --- | --- | --- | --- |
| Massa criada pela API | 4 amostras, 0 falhas | 4 amostras, 0 falhas | Aprovado; dashboard HTML presente. |
| Senhas propositalmente inválidas | 4 amostras, 4 falhas | 4 amostras, 0 falhas | Recusado, conforme exigido pelo teste. |

A execução negativa é uma prova de detecção de erro do plano, não uma falha não resolvida. Não somar suas oito amostras ao fluxo válido como medida de capacidade. Cada teste valida ambos os casos. O teste procura segredos emitidos, Basic codificado e formato JWT nos artefatos textuais; nenhuma correspondência encontrada. Arquivos de token/massa privados removidos ao encerrar. CSV e dashboard sanitizados foram preservados localmente em .firecrawl/etapa10a1-artifacts antes do clean; logs da seleção em .firecrawl/etapa10a1-smoke.log. Esses diretórios são ignorados e não foram publicados.

## Build padrão e revisões

Build padrão **BUILD SUCCESS às 23:02:24**, duração Maven **10 min e 21 s**: **778 testes em 72 relatórios XML**, zero falhas/erros/ignorados, incluindo os **11 cenários E2E** anteriores. Arquitetura, Spotless e limites JaCoCo IAM/enforcer aprovados. Log ignorado .firecrawl/etapa10a1-verify.log. Conferência dos XML confirmou ausência de JMeterSmoke na suíte padrão: o teste opt-in foi executado separadamente, não é contado nos 778 e não exige JMeter na CI normal. O clean removeu seus XML/artefatos anteriores do target; o log da seleção e a cópia sanitizada local preservam os resultados.

Revisão documental/sintática: 973 links locais válidos (README raiz/infra, guia JMeter e docs), 46 registros de etapas referenciados no guia, JMX XML válido, parsers PowerShell sem erros e git diff --check aprovado. Comparação em memória dos 13 arquivos alterados com cinco valores sensíveis locais: zero correspondências, sem imprimir os valores.

## Limites e próxima entrega

Oito amostras, duas threads, duas iterações e 1 s de ramp-up; sem patamar/ramp-down, instrumentação global, assinatura JWT verificada neste plano, usuários humanos, validação de recurso ou auditoria de carga. Não comprova 300 req/s, p95, SLA, memória estável ou capacidade. Windows/PowerShell foi exercitado; execução Linux/PowerShell 7 não foi exercitada. Baseline JMeter foi validada, sem afirmar versão mais recente. Criação manual pode deixar clientes parciais; interrupção forçada pode deixar CSV privado. A proteção dos relatórios corresponde ao runner/plano versionados, não a debug/listeners externos.

Próxima implementação: 10A2, massa humana, PKCE e demais cenários, subdividindo conforme necessário. Publicação/CI e verificações gerais de entrega permanecem pendentes.

Referências: [ADR-045](../adr/045-base-de-carga-jmeter.md), [operação](../../load-tests/jmeter/README.md), [runbook](../runbooks/carga-client-credentials-local.md), [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
