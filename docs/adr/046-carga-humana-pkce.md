# ADR-046 — Massa humana e jornadas PKCE no JMeter

- Data: 20/10/2026.
- Estado: implementada localmente na 10A2a; resultados nas evidências.

## Contexto e divisão

A base 10A1 cobre clientes máquina. A 10A2 inclui massa humana, OAuth PKCE, falhas e recursos/auditoria. Separar 10A2a (massa humana/PKCE/replay) de 10A2b (demais cenários) permite comprovar cookies, CSRF, state e isolamento antes de ampliar a carga.

## Decisão

Criar usuários, clientes confidenciais e políticas pela API, fora da parte cronometrada. E-mails usam example.test; senhas são geradas por CSPRNG e segredos de cliente são emitidos pelo servidor. A política de cada cliente é ALLOW_LIMITED, api:read e TTL de 120 s, sem recurso curinga. Arquivos privados ficam em diretórios ignorados e token administrativo é recebido por arquivo.

Usar JSR223Sampler com roteiro Groovy versionado e HttpClient Java 21: cada amostra representa a jornada completa, com cookie store próprio, S256/state novos, formulários CSRF reais e consentimento explícito quando necessário. Aceitar consentimento persistido, verificar callback sem acessá-lo e impedir redirects de navegação para outra origem. Conferir identidade do usuário/cliente no JWT decodificado, sem chamar isso de verificação criptográfica.

Um segundo rótulo prova rejeição de código consumido com invalid_grant. O runner verifica contrato/contagens, limpa contexto após falha e permite -WrongVerifier somente como injeção de erro. O teste opt-in JMeterHumanSmoke exige sucesso real, replay recusado, erro 400 para verifier errado e ausência das credenciais geradas nos artefatos textuais.

## Consequências e limites

O baseline é JMeter 5.6.3 da 10A1, sem download automático na CI. O build padrão não executa testes de carga. Não há mudança de aplicação, migração ou ACL. A fixture configura perfil de risco sintético apenas no ambiente descartável; o gerador não muda risco/MFA. MFA obrigatório faz o plano falhar.

elapsed mede múltiplas chamadas, criação do cliente HTTP e parsing; não é p95 do endpoint de token. Latency/Connect/bytes não são instrumentados nesse sampler. Mais de uma requisição corresponde a uma amostra. Sessões/consentimento/histórico permanecem no modo manual e podem alterar a jornada seguinte; não há limpeza de usuários/políticas automática ou garantia de rollback de preparação parcial. Claims, cookies e credenciais continuam apenas em memória/arquivos privados pelo ciclo documentado; não há promessa de apagar memória da JVM ou arquivos após encerramento forçado.

Próximo trabalho: 10A2b. Observabilidade/medição/capacidade continuam nas entregas 10B–10D.

Ver [operação](../../load-tests/jmeter/HUMAN-PKCE.md) e [evidências](../evidencias/etapa-10a2a.md).
