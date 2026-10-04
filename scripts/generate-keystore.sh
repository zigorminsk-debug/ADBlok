#!/usr/bin/env bash
# Создаёт ПОСТОЯННЫЙ ключ подписи ADBlok и печатает значения для секретов GitHub.
# Запускать один раз. Файл adblok-release.jks хранить в надёжном месте (в git он не коммитится).
set -euo pipefail

KEYSTORE=${1:-adblok-release.jks}
ALIAS=${ALIAS:-adblok}
STOREPASS=${STOREPASS:-$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)}

keytool -genkeypair -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity 10950 \
  -storepass "$STOREPASS" -keypass "$STOREPASS" \
  -dname "CN=ADBlok, OU=ADBlok, O=ADBlok, L=Minsk, C=BY"

echo
echo "Добавьте секреты в Settings → Secrets and variables → Actions:"
echo "  KEYSTORE_BASE64   = $(base64 -w0 "$KEYSTORE")"
echo "  KEYSTORE_PASSWORD = $STOREPASS"
echo "  KEY_ALIAS         = $ALIAS"
echo "  KEY_PASSWORD      = $STOREPASS"
