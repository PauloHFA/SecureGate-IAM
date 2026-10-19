# GitGuardian — triagem do vetor público TOTP

## Incidente 38407772

Análise em 2026-10-19: o alerta recebido contém duas ocorrências do mesmo valor, nos commits `90282bb` e `9039107`. O valor é a representação Base32 da sequência ASCII pública usada no Apêndice B da [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238.html#appendix-B). Não é uma semente extraída do Vault nem uma credencial de usuário.

- [TotpTest](../../iam-core/src/test/java/com/securegate/iam/identity/domain/TotpTest.java) verifica os resultados publicados da RFC, inclusive timestamps posteriores a 2038. A asserção também confere os bytes ASCII do vetor.
- [MfaServiceTest](../../iam-core/src/test/java/com/securegate/iam/identity/application/MfaServiceTest.java) faz o mock de `MfaSecretProtector.unprotect` retornar esse vetor para verificar códigos conhecidos em instantes fixos. Não acessa Vault nem contas reais.

A busca pelo literal no código versionado encontrou somente esses dois arquivos de teste. Em produção, `TotpSecret.generate()` gera a semente com `SecureRandom`; `MfaService` solicita sua proteção pelo adaptador Transit. O literal público não é o caminho de geração das sementes reais.

## Tratar o alerta recebido

Abra o [incidente 38407772](https://dashboard.gitguardian.com/workspace/752624/incidents/38407772?occurrence=305148502), revise as duas ocorrências e use a ação de ignorar com a justificativa de falso positivo/vetor público de teste. Sugestão de justificativa:

> Vetor público SHA1 da RFC 6238, Apêndice B, usado somente em testes determinísticos de TOTP e em mock de decifragem. Não corresponde a conta ou semente de produção. As duas ocorrências pertencem ao mesmo vetor.

Essa ação precisa ocorrer no workspace GitGuardian que gerou o alerta. A investigação local não altera o estado do incidente e não confirma que o check remoto passou. Se o alerta estiver associado a um PR, confira o resultado do check após a atualização no painel.

Alterações nos comentários do código atual não removem ocorrências dos commits antigos. Neste caso não há credencial operacional a revogar/rotacionar e não é necessário reescrever o histórico para retirar um vetor público de especificação.

Não exclua toda a pasta de testes nem desative o detector de alta entropia para resolver esse caso. Uma credencial real também pode ser incluída por engano em testes e deverá continuar sendo detectada. Qualquer alerta com valor diferente exige nova investigação.

## Configuração local e painel

Não foi adicionada configuração de supressão ao repositório. A [configuração do ggshield](https://docs.gitguardian.com/ggshield-docs/configuration) documenta as opções locais da CLI; ela não comprova o encerramento do incidente na integração GitHub. O painel também oferece [exclusões de padrões](https://docs.gitguardian.com/secrets-detection/customize-detection/exclusion-rules#secret-pattern-exclusion), mas uma exclusão ampla não é necessária para este incidente conhecido.

Nenhuma credencial, configuração do Vault ou implementação de MFA foi alterada nesta triagem. Os comentários identificam a origem pública do vetor; o comportamento dos testes permanece igual.
