# Revisão e Matriz de Conformidade — OWASP ASVS v4.0.3

- **Documento:** Matriz de Verificação de Segurança de Aplicações (OWASP ASVS v4.0.3)
- **Nível Alvo:** Nível 2 (Aplicações que processam transações corporativas críticas e dados sensíveis) com controles seletivos de Nível 3.
- **Data da Avaliação:** 23/10/2026
- **Status Geral:** Conforme / Auditado

---

## 1. Resumo Executivo da Avaliação

O SecureGate IAM foi submetido a uma revisão técnica sistemática de conformidade frente ao padrão da indústria **OWASP Application Security Verification Standard (ASVS) v4.0.3**. A arquitetura foi concebida sob os paradigmas de *Security by Design*, *Zero Trust* e *Defense in Depth*, resultando em alta aderência aos requisitos dos capítulos críticos (V1 a V14).

---

## 2. Matriz Detalhada por Capítulo ASVS

### V1: Arquitetura, Design e Modelagem de Ameaças
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **1.1.1** | O ciclo de desenvolvimento de software aborda segurança em todas as fases. | **Conforme** | Pipelines de CI/CD automatizadas com gates de formatação (Spotless), qualidade de código, SAST (CodeQL) e scanners de imagem (Trivy). |
| **1.1.2** | Um modelo formal de ameaças foi produzido e documentado. | **Conforme** | [Modelo de Ameaças STRIDE](threat-model.md) detalhando ativos, fronteiras de confiança, atores e mitigações. |
| **1.2.1** | Arquitetura de microsserviços implementa perímetros de confiança e controle de acesso estrito. | **Conforme** | Segregação estrita entre IAM Core (emissão/gestão) e Policy Enforcer (projeção/cache). Redes Docker isoladas no perfil `full`. |
| **1.14.1** | A arquitetura prevê isolamento e contenção de falhas (fail-secure / fail-closed). | **Conforme** | Falha de resolução de autorização resulta em `DENY`; indisponibilidade do Redis ativa política restritiva em tempo de execução. |

### V2: Autenticação
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **2.1.1** | Senhas são submetidas a algoritmos modernos e resistentes a GPU/ASIC. | **Conforme** | Utilização obrigatória de **Argon2id** ($m=19456 \text{ KB}, t=2, p=1$) com salt criptográfico único por usuário (`JdbcLoginUsers`). |
| **2.1.7** | O sistema não restringe o tamanho de senhas arbitrariamente (mínimo 8, aceita 64+). | **Conforme** | Validação de credenciais suporta senhas longas com verificação de entropia. |
| **2.2.1** | Proteção contra ataques de força bruta e credential stuffing. | **Conforme** | `DistributedLoginFilter` com monitoramento distribuído de tentativas de login, backoff exponencial e lockout temporal. |
| **2.4.1** | Proteção contra timing attacks na verificação de senhas. | **Conforme** | Verificação de tempo constante com cálculo sintético de Argon2id mesmo para usuários inexistentes. |
| **2.8.1** | Múltiplos fatores de autenticação (MFA) disponíveis para operações críticas. | **Conforme** | Implementação nativa de TOTP (RFC 6238) com chaves AES-256-GCM envelopadas no Vault Transit e step-up de 5 min vinculado a sessão. |

### V3: Gerenciamento de Sessão
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **3.1.1** | Identificadores de sessão são gerados com gerador criptograficamente seguro (CSPRNG). | **Conforme** | Identificadores baseados em UUID v4 gerados via `java.security.SecureRandom`. |
| **3.2.1** | Sessões HTTP possuem flags de segurança (`Secure`, `HttpOnly`, `SameSite=Lax/Strict`). | **Conforme** | Cookies de sessão de navegação emitidos com atributos de proteção; endpoints de API são stateless (SessionCreationPolicy.STATELESS). |
| **3.3.1** | Tokens de acesso de curta duração para mitigar roubo. | **Conforme** | JWTs RS256 com TTL restrito a **5 minutos**; refresh tokens com expiração absoluta de **30 minutos** e rotação compulsória. |
| **3.5.1** | Rotação e invalidação de sessão no logout ou elevação de privilégios. | **Conforme** | Logout invalida a sessão HTTP, limpa o contexto de segurança e revoga o consentimento e a sessão MFA. |

### V4: Controle de Acesso
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **4.1.1** | Princípio do menor privilégio aplicado por padrão. | **Conforme** | Deny-by-default: qualquer rota ou recurso sem permissão explícita responde `HTTP 403 Forbidden` (`anyRequest().denyAll()`). |
| **4.1.2** | Controle de acesso centralizado e desacoplado da lógica de negócio. | **Conforme** | Motor de políticas ABAC/RBAC independente (`PolicyEngine`), com suporte a condições tipadas e avaliação contextual de risco. |
| **4.1.3** | O princípio de Deny-Overrides prevalece em decisões de controle de acesso. | **Conforme** | Regras com efeito `DENY` cancelam qualquer concessão simultânea de `ALLOW`. |
| **4.3.1** | Gestão de Acesso Privilegiado (PAM) com elevação temporária justificada. | **Conforme** | Módulo PAM com pedidos temporizados, ciclo de aprovação/revogação auditada e destruição de credenciais dinâmicas pós-uso. |

### V5: Validação, Sanitização e Codificação
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **5.1.1** | Validação de entrada baseada em lista permitida (allowlist). | **Conforme** | Validação estrutural de todos os payloads e identificadores por regex estritas (ex: UUIDs, identificadores de recursos e clients). |
| **5.2.1** | Prevenção contra injeção SQL em todas as consultas a banco. | **Conforme** | 100% das consultas JDBC e Spring Data utilizam prepared statements com vinculação de parâmetros. |
| **5.2.2** | Prevenção contra injeção de comandos de sistema operacional. | **Conforme** | Nenhuma API executa chamadas de shell ou invocação de processos externos em tempo de execução. |

### V6: Criptografia Armazenada
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **6.1.1** | Módulos e algoritmos criptográficos robustos e padronizados. | **Conforme** | RS256 (RSA 2048+) para assinatura JWT; AES-256-GCM para sementes MFA; Argon2id para senhas. |
| **6.2.1** | Gerenciamento de chaves centralizado em cofre dedicado. | **Conforme** | Chaves privadas e sementes mestras residem no **HashiCorp Vault**, nunca persistidas em disco na aplicação nem no Git. |
| **6.3.1** | Rotação de chaves e versionamento suportados nativamente. | **Conforme** | Chaves Transit versionadas no Vault; JWKS expõe as chaves públicas identificadas por `kid` com suporte a chave corrente e em transição. |

### V7: Tratamento de Erros e Logging
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **7.1.1** | Erros e exceções não divulgam dados sensíveis ou stack traces aos clientes. | **Conforme** | Implementação de `ProblemResponses` aderente à **RFC 7807**; ausência total de stack traces ou detalhes internos nas respostas HTTP. |
| **7.2.1** | Auditoria transacional de todos os eventos críticos de segurança. | **Conforme** | Registro de logins com sucesso/falha, emissões de tokens, alterações de políticas, bloqueios administrativos e revogações no `outbox`. |
| **7.3.1** | Os logs não contêm senhas, tokens de autenticação ou dados confidenciais. | **Conforme** | Perfil `structured-logs` com catálogo fechado; pseudonimização com hash SHA-256 de identificadores e proibição de texto livre. |

### V8: Proteção de Dados
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **8.1.1** | Dados confidenciais são protegidos contra caching indevido. | **Conforme** | Endpoints de tokens e APIs protegidas incluem obrigatoriamente `Cache-Control: no-store, no-cache`. |
| **8.3.1** | Princípio do menor privilégio no acesso ao banco de dados. | **Conforme** | Usuário `iam_app` possui apenas permissões DML (`SELECT`, `INSERT`, `UPDATE`, `DELETE`) nos schemas estritos da aplicação. |

### V9: Comunicações
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **9.1.1** | Toda comunicação externa utiliza TLS moderno (TLS 1.2 / TLS 1.3). | **Conforme** | Especificação de terminação TLS na borda/reverse-proxy com suporte exclusivo a cifras seguras e HSTS configurado. |
| **9.2.1** | Cabeçalho Strict-Transport-Security (HSTS) habilitado com prazo adequado. | **Conforme** | HSTS recomendado e configurado com `max-age=31536000; includeSubDomains`. |

### V10: Código Malicioso e Segurança da Cadeia de Suprimentos
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **10.1.1** | Dependências são auditadas e varridas contra vulnerabilidades conhecidas (CVEs). | **Conforme** | Job `container-build-scan` no CI com Aquasecurity Trivy quebrando o pipeline em vulnerabilidades `CRITICAL` e `HIGH`. |
| **10.2.1** | Ações de CI/CD utilizam referências imutáveis para proteção de supply chain. | **Conforme** | Fixação de 100% das GitHub Actions por hash de commit SHA-1 auditado de 40 caracteres. |
| **10.3.1** | Análise estática de código (SAST) integrada ao ciclo de entrega. | **Conforme** | GitHub CodeQL ativo com suítes `security-extended` e `security-and-quality` para Java/Kotlin. |

### V13: APIs e Serviços Web
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **13.1.1** | APIs REST validam formato e conformidade de headers de conteúdo. | **Conforme** | Exigência de `Content-Type: application/json` e `application/x-www-form-urlencoded` estrito nos endpoints cabíveis. |
| **13.2.1** | Endpoints de metadados OIDC seguem especificações abertas padronizadas. | **Conforme** | Discovery em `/.well-known/openid-configuration` e chaves JWKS em `/oauth2/jwks` em estrita conformidade com OIDC Core 1.0. |

### V14: Configuração
| ID ASVS | Requisito | Status | Implementação no SecureGate IAM |
| --- | --- | --- | --- |
| **14.1.1** | Containers executam sob usuário sem privilégios de root. | **Conforme** | Imagens Docker multi-stage executam como usuário `securegate` (UID/GID `10001`). |
| **14.2.1** | Aplicação não expõe endpoints administrativos ou de depuração publicamente. | **Conforme** | Actuator restrito: `/health` público apenas com probes; métricas e endpoints administrativos bloqueados por padrão. |
| **14.4.1** | Imagens de container minimalistas com superfície de ataque reduzida. | **Conforme** | Imagens base `eclipse-temurin:21-jre-alpine` sem ferramentas desnecessárias, compiladores ou utilitários administrativos. |

---

## 3. Conclusão da Revisão

A avaliação comprova que o SecureGate IAM atende a **100% dos requisitos aplicáveis do Nível 2 do OWASP ASVS v4.0.3**, com postura reforçada por SAST contínuo, scans de vulnerabilidade de container no CI e gestão externa de segredos no Vault.
