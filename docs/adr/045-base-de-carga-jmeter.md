# ADR-045 — Base de carga com JMeter e dados sintéticos

- Data: 20/10/2026.
- Estado: implementada localmente na 10A1; resultados nas evidências.

## Contexto e divisão

A etapa 10 exige carga de OAuth, login, revogação e auditoria. Um único pacote dificultaria distinguir correção do plano, dados, observabilidade e capacidade. Dividir 10A em 10A1 (clientes máquina e smoke) e 10A2 (massa humana e demais jornadas), mantendo 10B–10D para observabilidade, medição e ajustes.

## Decisão

Versionar plano JMeter, propriedades de gravação e scripts PowerShell. Criar clientes pela API administrativa antes da parte cronometrada, usando IDs reproduzíveis e segredos novos emitidos pelo servidor. Receber token administrativo por arquivo, e manter massa com credenciais em diretórios ignorados; não semear SQL nem embutir credenciais no JMX ou argumentos.

Executar emissão válida e invalid_client como rótulos separados. A rejeição esperada só passa quando status e corpo correspondem ao contrato. O runner inspeciona as amostras e contagens, pois a saída normal do processo não garante sucesso das asserções. Corpos/headers são limpos no plano e desabilitados na persistência; testes procuram credenciais geradas e JWT nos artefatos.

Usar JMeter 5.6.3 como baseline exercitada, instalado explicitamente com integridade conferida. Não presumir que seja a versão mais recente. O teste opt-in JMeterSmoke reutiliza infraestrutura descartável/JARs e chama os scripts reais. O build padrão continua independente de JMeter, sem download ou execução de carga em PR.

## Consequências

A 10A1 entrega um smoke de contrato, não um benchmark. Não modifica código de produção ou migrações. Resultados agregados misturam fluxos e não medem exclusivamente emissão válida. Não há usuário humano, PKCE, consumo de recurso ou revogação neste plano ainda. A fixture inicia enforcer/broker/Redis embora o plano só exercite emissão; esse ambiente não define a topologia de uma futura medição de capacidade.

Massa parcial pode deixar clientes no modo manual e segredos em CSV exigem proteção local. Fechamento forçado pode deixar arquivos temporários. A verificação de artefatos cobre o runner versionado, não garante sanitização de qualquer extensão/debug externo. O plano verifica formato de JWT, não sua assinatura; verificação criptográfica continua nos testes funcionais existentes.

Ver [operação](../../load-tests/jmeter/README.md) e [evidências](../evidencias/etapa-10a1.md).
