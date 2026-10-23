# Runbook — Hardening e Postura de Segurança

- **Público:** Engenheiros de Segurança, SREs e Operadores de Infraestrutura
- **Objetivo:** Estabelecer diretrizes operacionais para terminação TLS, políticas CORS, cabeçalhos de segurança HTTP, mitigação de DoS e isolamento de segredos.
- **Ambiente:** Produção, Homologação e Staging

---

## 1. Diretrizes de Comunicação e Terminação TLS

### 1.1. Arquitetura de Borda (Reverse Proxy / Ingress)
O SecureGate IAM é concebido como um serviço de backend stateless executando em rede privada. Em ambientes produtivos, a terminação TLS deve ocorrer obrigatoriamente no Ingress / Gateway de borda (ex: Nginx, Traefik, AWS ALB ou Cloudflare):

1. **Protocolos Permitidos:** Apenas **TLS 1.2** e **TLS 1.3**. Os protocolos SSLv3, TLS 1.0 e TLS 1.1 devem ser desabilitados na borda.
2. **Suítes de Cifra Recomendadas (TLS 1.2/1.3):**
   - `TLS_AES_256_GCM_SHA384`
   - `TLS_CHACHA20_POLY1305_SHA256`
   - `TLS_AES_128_GCM_SHA256`
   - `ECDHE-ECDSA-AES256-GCM-SHA384`
   - `ECDHE-RSA-AES256-GCM-SHA384`
3. **HTTP Strict Transport Security (HSTS):**
   O proxy de borda deve injetar o cabeçalho HSTS para assegurar que conexões de navegadores sejam forçadas para HTTPS:
   ```http
   Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
   ```

### 1.2. Propagação de Cabeçalhos Reversos no Spring Boot
Para que o Spring Boot reconheça o esquema HTTPS original e o IP real do cliente atrás do proxy de terminação TLS, as seguintes variáveis de ambiente devem ser fornecidas no runtime do container:
```properties
SERVER_FORWARD_HEADERS_STRATEGY=framework
SERVER_TOMCAT_REMOTE_IP_HEADER=x-forwarded-for
SERVER_TOMCAT_PROTOCOL_HEADER=x-forwarded-proto
```

---

## 2. Política de CORS (Cross-Origin Resource Sharing)

### 2.1. Princípio de Restrição de Origem
O SecureGate IAM não permite acesso de origens arbitrárias (`*`) em conjunto com credenciais de usuário.

1. **Endpoints Públicos de Metadados OIDC:**
   - `/.well-known/openid-configuration` e `/oauth2/jwks` podem ser consumidos por aplicações Single Page (SPA) autorizadas.
2. **Endpoints de Emissão e Administração:**
   - `/oauth2/token`: Consumido por clientes backend autorizados ou SPAs registradas via PKCE.
   - `/api/admin/**`: Estritamente restrito a acessos internos ou consoles administrativos de mesma origem. Proibido qualquer cabeçalho `Access-Control-Allow-Origin: *`.
3. **Headers Permitidos:** Apenas os headers necessários para a operação:
   `Authorization`, `Content-Type`, `X-Correlation-ID`, `X-Bootstrap-Token`.

---

## 3. Catálogo de Cabeçalhos de Segurança HTTP

A aplicação emite e exige a presença dos seguintes cabeçalhos de proteção contra ataques clássicos de web:

| Cabeçalho | Valor Padrão / Recomendado | Propósito de Segurança |
| --- | --- | --- |
| **X-Content-Type-Options** | `nosniff` | Impede o navegador de interpretar arquivos de forma diferente do MIME declarado (MIME sniffing). |
| **X-Frame-Options** | `DENY` | Impede ataques de *Clickjacking*, proibindo renderização em `<frame>`, `<iframe>` ou `<object>`. |
| **Referrer-Policy** | `no-referrer` | Impede o vazamento de tokens de autorização ou parâmetros de URL no cabeçalho Referer. |
| **Content-Security-Policy (CSP)** | `default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'` | Mitiga Cross-Site Scripting (XSS) e injeção de scripts maliciosos. |
| **Permissions-Policy** | `geolocation=(), camera=(), microphone=()` | Desabilita o acesso do navegador a recursos de hardware sensíveis. |
| **Cache-Control** | `no-store, no-cache, must-revalidate` | Impede cache de respostas contendo credenciais ou dados de usuário em intermediários. |

---

## 4. Proteções Contra Negação de Serviço (DoS) e Rate Limiting

### 4.1. Conexões e Threads no Tomcat Embutido
As configurações de contenção de conexões no `application.yml` protegem o serviço contra exaustão de descritores de arquivo e conexões lentas (Slowloris):
```yaml
server:
  tomcat:
    connection-timeout: 5000ms
    max-connections: 10000
    threads:
      max: 200
      min-spare: 20
```

### 4.2. Concorrência Criptográfica no Vault
Para evitar que uma sobrecarga de emissão de tokens derrube o HashiCorp Vault, o semáforo de assinatura é delimitado por variável de ambiente:
```properties
IAM_TRANSIT_MAX_CONCURRENT_REQUESTS=30
IAM_TRANSIT_TIMEOUT=5000ms
```

### 4.3. Bloqueio Distribuído de Força Bruta
O `DistributedLoginFilter` utiliza contadores atômicos no Redis para limitar tentativas consecutivas de autenticação inválidas:
- **Limite por Conta:** 5 tentativas incorretas resultam em bloqueio temporário de 15 minutos.
- **Limite por IP:** 20 tentativas incorretas em janela de 10 minutos acionam quarentena temporária do endereço de origem.

---

## 5. Gerenciamento e Isolamento de Segredos

1. **Zero Credenciais em Repositório:** Nenhuma senha, chave privada ou token é comitado em arquivos `.yml` ou scripts no repositório.
2. **AppRole com Arquivos Somente-Leitura:**
   O `role-id` e `secret-id` são injetados em arquivos protegidos com permissão `0400` no caminho `/etc/securegate/`. O token de serviço resultante tem vida útil máxima de 1 hora e é revogado após o bootstrap.
3. **Execução sem Root em Containers:**
   Todas as imagens de container rodam com o usuário `securegate` (UID `10001`), impedindo escrita arbitrária em diretórios do sistema operacional ou montagens do host.
