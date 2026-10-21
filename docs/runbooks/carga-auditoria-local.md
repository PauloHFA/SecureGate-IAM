# Runbook — Smoke de auditoria central (10A2b3)

1. Preferir o teste opt-in JMeterAuditSmoke conforme [AUDIT.md](../../load-tests/jmeter/AUDIT.md), com serviços descartáveis e JMeter instalado/conferido.
2. No modo manual, usar stack dedicada: cada iteração cria dois usuários ativos e fatos permanentes, sem limpeza automática.
3. Preparar logins/partições de auditoria existentes e iniciar IAM com bootstrap, publisher, consumer, partitions e query conforme operação. Readiness sozinho não comprova progresso de ingestão.
4. Criar arquivo administrativo privado sem imprimir token. Passar somente seu caminho a Run-Audit; começar com uma thread/uma iteração.
5. Conferir quatro rótulos com threads × loops amostras e zero falhas, dashboard e run.json. Não interpretar usersPlanned como total confirmado após falha.
6. Exigir duas páginas distintas para mesma janela/correlação/tipo e recursos correspondentes aos usuários produzidos. 200 vazio não aprova visibilidade; 401 sem credencial aprova a negativa.
7. Executar -WrongCorrelation em diretório novo: deve falhar visibilidade com 200 vazio e continuação local sem cursor; negativa sem credencial permanece 401.
8. Diante de timeout/503/404, conferir flags de consulta/ingestão, grants, partições, publisher, fila/consumidor e banco conforme [8B](consumidor-auditoria-local.md) e [8C](consulta-auditoria-local.md). Não purgar filas nem fabricar fatos para aprovar.
9. Remover arquivo administrativo em finally e revisar artefatos antes de compartilhar. Logs brutos dos serviços têm escopo distinto da redação do plano.
10. Registrar [evidências](../evidencias/etapa-10a2b3.md), atualizar [guia](../GUIA-DE-IMPLEMENTACAO.md) e seguir para observabilidade 10B; nenhuma meta de capacidade/SLA é inferida do smoke.
