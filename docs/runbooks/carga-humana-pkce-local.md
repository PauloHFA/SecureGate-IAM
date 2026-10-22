# Carga humana e PKCE — 10A2a

Consultar o [roteiro executável JMeter humano](../../load-tests/jmeter/HUMAN-PKCE.md) para instalação, comandos, massa, contratos e recuperação.

1. Executar JMeterHumanSmoke opt-in com Java 21, PowerShell, Docker e JMeter 5.6.3.
2. Usar o ambiente descartável recomendado; somente nele o teste configura a rede/sensibilidade sintética de baixo risco.
3. Conferir as duas jornadas e duas rejeições de replay em summary.csv. Identidade e TTL são conferidos dentro da jornada.
4. Conferir que a injeção WrongVerifier falha com HTTP 400 real; não contar esse ensaio como sucesso/capacidade.
5. No modo manual, preparar uma instância dedicada. Mudanças globais de risco são explícitas no guia e não fazem parte do gerador.
6. Proteger CSV/token em diretórios ignorados; criação parcial exige namespace novo ou recriação do ambiente.
7. Ler elapsed por rótulo como duração agregada. O plano não mede individualmente /oauth2/token, MFA ou recursos protegidos.
8. Registrar ambiente/parâmetros e revisar artefatos antes de compartilhar; não habilitar logs privados de HTTP.

Sem nova migração; V22 permanece atual. A 10A2b tratará os demais cenários. Ver [ADR-046](../adr/046-carga-humana-pkce.md), [evidências](../evidencias/etapa-10a2a.md) e [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
