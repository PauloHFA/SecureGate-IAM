# Runbook — Smoke de recurso protegido e revogação (10A2b2)

1. Preferir o teste opt-in com serviços descartáveis, seguindo [RESOURCE-REVOCATION.md](../../load-tests/jmeter/RESOURCE-REVOCATION.md).
2. No modo manual, iniciar IAM com Authorization/bootstrap/enforcement/publisher e enforcer consumidor em terminais separados; confirmar risco de login compatível. Usar stack dedicada: a política account:self/read adicionada é geral para sujeitos elegíveis.
3. Criar massa via New-HumanMass -IncludeAccountPolicy, token administrativo por arquivo privado, namespace e CSV novos. Não imprimir segredos.
4. Executar Run-ResourceRevocation com instalação JMeter local e diretório de saída novo; começar com uma thread/uma iteração.
5. Conferir cinco rótulos em summary.csv, cada um com threads × loops amostras e zero falhas, além de dashboard/run.json.
6. Interpretar 200 no POST de revogação como aceite; exigir 401 posterior com validade residual. Não interpretar polling como latência de propagação distribuída: há ledger local. O teste isolado confirma separadamente publicação/projeção.
7. Executar controle -SkipRevocation em diretório novo; deve falhar resource.revoked.expected com 200 real, sem novo fato token.revoked.
8. Se houver 403/429/503, investigar política/risco/bloqueio/dependências; não limpar Redis ou relaxar controles para aprovar.
9. Rever artefatos antes de compartilhar. Remover arquivos administrativos/CSV privados; no modo manual registros/políticas/sessões permanecem, sem rollback automático. Nunca publicar logs brutos de serviços.
10. Registrar resultados/limites nas [evidências](../evidencias/etapa-10a2b2.md), atualizar [guia](../GUIA-DE-IMPLEMENTACAO.md) e avançar para auditoria 10A2b3.
