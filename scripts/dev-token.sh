#!/usr/bin/env sh
# Generates an HS256 JWT for local development only.
# Usage: scripts/dev-token.sh [client-id] [scopes]
set -eu

CLIENT_ID="${1:-local-client}"
SCOPES="${2:-activation:read activation:write}"
SECRET="${JWT_DEV_SECRET:-local-dev-secret-change-me-0123456789}"
AUDIENCE="${JWT_AUDIENCE:-activation-service}"
NOW=$(date +%s)
EXP=$((NOW + 3600))

b64url() {
    openssl base64 -A | tr '+/' '-_' | tr -d '='
}

HEADER=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
PAYLOAD=$(printf '{"sub":"%s","aud":"%s","scope":"%s","iat":%s,"exp":%s}' \
    "$CLIENT_ID" "$AUDIENCE" "$SCOPES" "$NOW" "$EXP" | b64url)
SIGNATURE=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)

printf '%s.%s.%s\n' "$HEADER" "$PAYLOAD" "$SIGNATURE"
