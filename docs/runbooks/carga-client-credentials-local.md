# Carga local: Client Credentials — 10A1

O roteiro executável, parâmetros, geração da massa e recuperação estão no [guia JMeter](../../load-tests/jmeter/README.md). Começar pelo smoke isolado; o modo manual cria clientes no ambiente local apontado.

1. Instalar JMeter 5.6.3 com integridade conferida, Java 21, PowerShell e Docker.
2. Executar a seleção opt-in JMeterSmoke indicada no guia. Não é necessário usar .env ou provisionar a stack persistente.
3. Conferir token.success e token.invalid_client.expected em summary.csv; os dois rótulos têm quatro amostras sem falha no padrão.
4. Conferir que a massa propositalmente inválida foi rejeitada pelo runner; o teste automatizado exige esse resultado.
5. Consultar dashboard/index.html por rótulo; não interpretar o agregado como latência de emissão válida.
6. No modo manual, usar token por arquivo, namespace novo e diretórios ignorados. Se a criação falhar parcialmente, recriar o ambiente de teste ou usar namespace/arquivo novos; segredo não é reentregue por GET.
7. Remover credenciais locais quando não forem necessárias e revisar artefatos antes de compartilhar. Não habilitar logs HTTP de debug.
8. Registrar data/versões/parâmetros e resultados. Metas de desempenho exigirão ensaios maiores com instrumentação e ambiente documentado.

Não há alteração de schema ou aplicação nesta entrega. PKCE, massa humana e demais fluxos ficam para 10A2; observabilidade e medição para 10B/10C. Consultar [ADR-045](../adr/045-base-de-carga-jmeter.md), [evidências](../evidencias/etapa-10a1.md) e [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
