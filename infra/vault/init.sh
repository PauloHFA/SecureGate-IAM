#!/bin/sh
set -eu
umask 077

if ! vault secrets list -format=json | grep -q '"secret/"'; then
  vault secrets enable -path=secret kv-v2 >/dev/null
fi
if ! vault secrets list -format=json | grep -q '"transit/"'; then
  vault secrets enable transit >/dev/null
fi
if ! vault auth list -format=json | grep -q '"approle/"'; then
  vault auth enable approle >/dev/null
fi

# A leitura evita recriar ou rotacionar uma chave existente.
if ! vault read transit/keys/securegate-jwt >/dev/null 2>&1; then
  vault write transit/keys/securegate-jwt type=rsa-3072 \
    exportable=false allow_plaintext_backup=false >/dev/null
fi

if ! vault read transit/keys/securegate-mfa >/dev/null 2>&1; then
  vault write transit/keys/securegate-mfa type=aes256-gcm96 \
    exportable=false allow_plaintext_backup=false >/dev/null
fi
if [ "$(vault read -field=type transit/keys/securegate-mfa)" != aes256-gcm96 ] || \
   [ "$(vault read -field=exportable transit/keys/securegate-mfa)" != false ] || \
   [ "$(vault read -field=allow_plaintext_backup transit/keys/securegate-mfa)" != false ]; then
  echo 'Chave securegate-mfa existente incompativel; bootstrap interrompido.' >&2
  exit 1
fi

if ! vault kv get secret/securegate/iam-core >/dev/null 2>&1; then
  vault kv put secret/securegate/iam-core \
    postgres_host=postgres postgres_database="$POSTGRES_DB" \
    postgres_username="$POSTGRES_USER" postgres_password="$POSTGRES_PASSWORD" \
    redis_host=redis redis_password="$REDIS_PASSWORD" \
    rabbitmq_host=rabbitmq rabbitmq_vhost="$RABBITMQ_VHOST" \
    rabbitmq_username="$RABBITMQ_USER" rabbitmq_password="$RABBITMQ_PASSWORD" >/dev/null
fi
if ! vault kv get secret/securegate/policy-enforcer >/dev/null 2>&1; then
  vault kv put secret/securegate/policy-enforcer \
    redis_host=redis redis_password="$REDIS_PASSWORD" \
    rabbitmq_host=rabbitmq rabbitmq_vhost="$RABBITMQ_VHOST" \
    rabbitmq_username="$RABBITMQ_USER" rabbitmq_password="$RABBITMQ_PASSWORD" >/dev/null
fi

if ! vault kv get secret/securegate/iam-runtime >/dev/null 2>&1; then
  vault kv put secret/securegate/iam-runtime \
    redis_password="$REDIS_PASSWORD" rabbitmq_username="$RABBITMQ_USER" \
    rabbitmq_password="$RABBITMQ_PASSWORD" >/dev/null
fi

vault policy write securegate-key-operator /bootstrap/key-operator.hcl >/dev/null
for role in iam-core policy-enforcer iam-runtime; do
  vault policy write "securegate-$role" "/bootstrap/$role.hcl" >/dev/null
  vault write "auth/approle/role/$role" \
    token_policies="securegate-$role" token_ttl=1h token_max_ttl=4h \
    secret_id_ttl=24h secret_id_num_uses=0 >/dev/null
  role_id=$(vault read -field=role_id "auth/approle/role/$role/role-id")
  valid=false
  if [ -f "/output/$role-role-id" ] && [ -f "/output/$role-secret-id" ] && \
     [ "$(cat "/output/$role-role-id")" = "$role_id" ]; then
    if vault write "auth/approle/role/$role/secret-id/lookup" \
      secret_id="$(cat "/output/$role-secret-id")" >/dev/null 2>&1; then
      valid=true
    fi
  fi
  if [ "$valid" != true ]; then
    vault write -field=secret_id -f "auth/approle/role/$role/secret-id" \
      >"/output/$role-secret-id.tmp"
    mv "/output/$role-secret-id.tmp" "/output/$role-secret-id"
    printf '%s' "$role_id" >"/output/$role-role-id"
  fi
done

echo 'Vault inicializado: KV, Transit RSA-3072/AES-256-GCM, politicas e AppRoles prontos.'
