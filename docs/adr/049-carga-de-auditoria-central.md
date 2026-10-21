# ADR-049 — Smoke JMeter de auditoria central

- Data: 21/10/2026
- Estado: aceita para implementação local da 10A2b3

## Contexto

Os planos máquina/PKCE/negativas/revogação já existem. Faltava um plano que gerasse fatos pela aplicação e verificasse ingestão/consulta do log central, sem se limitar à Outbox ou fabricar envelopes diretamente no store.

## Decisão

Produzir dois usuários sintéticos por iteração pela API administrativa, com CSPRNG/UUID e correlação comum, e consultar identity.user.created via API central com limit=1 e janela fixa. Exigir duas páginas/cursor real, recursos exatos e eventos distintos. Separar produção, visibilidade por polling, continuação e recusa sem credencial em quatro rótulos. Usar arquivo privado para credencial, clientes HTTP sem cookies/redirects e resultados redigidos.

Teste opt-in provisiona a identidade restrita existente em ambiente descartável, habilita consumidor/consulta e compara envelopes centrais com Outbox fora do cronômetro. Correlação inexistente como controle deve falhar com HTTP 200 vazio. Helper audit-only não ativa PAM nem modifica o comportamento padrão da fixture. Aplicação, schema (V22), ACL e stack persistente permanecem sem mudanças.

## Consequências

Fecha a cobertura funcional dos cenários 10A com planos repetíveis. Criação de usuário inclui hashing/escrita e deixa contas ativas em execução manual; exigir ambiente dedicado. Acesso administrativo é necessário para produzir/consultar; nenhum token vai aos argumentos/resultados. Interrupção pode deixar arquivo privado. Polling não mede ingestão isolada nem SLA/capacidade; instrumentação, ensaios sustentados e ajustes ficam para 10B–10D. Não cobre todos os filtros/tipos nem retenção/replay/indisponibilidade nesta entrega.

[Operação](../../load-tests/jmeter/AUDIT.md), [evidências](../evidencias/etapa-10a2b3.md) e [passo a passo](../GUIA-DE-IMPLEMENTACAO.md).
