# Carga de falhas de login — 10A2b1

Consultar [LOGIN-FAILURES.md](../../load-tests/jmeter/LOGIN-FAILURES.md) para os comandos e contratos completos.

1. Preparar Java 21, Docker, PowerShell e JMeter 5.6.3.
2. Executar JMeterLoginFailureSmoke opt-in; a fixture cria usuário e confirma o candidato ausente sem usar a stack persistente.
3. Conferir uma amostra aprovada para senha incorreta e uma para usuário inexistente; ambas devem manter a sessão anônima e a mensagem genérica.
4. Conferir a detecção de autenticação inesperada na injeção de senha correta e dois fatos reais na Outbox após o ensaio normal.
5. Para execução manual, usar instância dedicada, massa privada e namespace/diretório novos. Não limpar Redis ou reduzir controles para repetir tentativas.
6. Tratar 403/429/503 como falhas do cenário, conforme o motivo; elas não são negativas de credenciais aprovadas.
7. Ler elapsed como duração agregada de quatro chamadas; não inferir latência de login, tempo constante ou capacidade.
8. Remover credenciais privadas quando não forem necessárias e revisar relatórios antes de compartilhar.

Sem nova migração; V22 permanece atual. Próximas entregas: 10A2b2 (recursos/revogação), 10A2b3 (auditoria), depois observabilidade/medição. Ver [ADR-047](../adr/047-carga-de-falhas-de-login.md), [evidências](../evidencias/etapa-10a2b1.md) e [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
