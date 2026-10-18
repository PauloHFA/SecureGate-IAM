# ADR-002 — Assinatura JWT com Vault Transit

- Status: aceito.
- Data: 18/10/2026.
- Implementação: etapa 4; preparação do Vault na etapa 1.

## Contexto e decisão

O Authorization Server emitirá JWTs RS256 e publicará material público via JWKS. Usar Vault Transit desde a primeira implementação de emissão, com chave RSA de 3072 bits não exportável. A chave privada não será armazenada na aplicação.

Criar uma porta de assinatura no contexto Authorization e um adaptador de infraestrutura para o Vault. O adaptador deve assinar o conteúdo JWS com RSA PKCS#1 v1.5 e SHA-256, correspondente a RS256. Confirmar os parâmetros da API escolhida, o tratamento de conteúdo pré-hash e as codificações para evitar hash duplo ou formato incompatível.

O `kid` identifica a versão da chave. O JWKS publica material público das versões aceitas. A versão anterior permanece disponível por toda a vida máxima dos tokens emitidos com ela, incluindo a tolerância de relógio definida.

## Operação

- A aplicação recebe permissões mínimas para assinar e obter material público; rotação usa identidade operacional separada.
- Não conceder à aplicação permissão de exportação, exclusão ou rotação da chave.
- Se o Vault não puder assinar, novas emissões falham de maneira controlada, sem fallback para chave privada local.
- Definir timeouts, limites de concorrência e métricas para a assinatura remota.
- Material público pode ser cacheado; isso permite verificar tokens existentes, respeitando sessão/revogação, mas não emitir tokens sem o Vault.
- Vault dev é exclusivo do ambiente local. Produção exige persistência, TLS, unseal e recuperação documentada.

## Evidências exigidas

1. JWT verificado por biblioteca independente usando o JWKS publicado.
2. Correspondência entre `kid`, versão do Transit e chave pública.
3. Rotação com tokens emitidos antes e depois da troca.
4. Indisponibilidade do Vault impede emissão sem revelar segredos.
5. Funcionamento com política restrita e sem chave privada em arquivos, imagens ou variáveis de ambiente.

## Consequências

O Vault integra o caminho crítico de emissão. Os testes de carga devem medir o custo da assinatura remota. A rotação precisa coordenar novas assinaturas com publicação e retenção das chaves públicas.
