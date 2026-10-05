#!/usr/bin/env bash
# Pengram: создание собственного ключа подписи и секретов для GitHub Actions.
#
# Ключ в репозитории (TMessagesProj/config/release.keystore) — тестовый: его
# пароли известны всем, кто видел исходники. Релиз надо подписывать своим ключом,
# иначе обновление поверх беты не встанет, а чужой сможет выпустить «апдейт».
#
# Запуск:  bash scripts/new-keystore.sh
# Нужен установленный JDK (команда keytool).

set -euo pipefail

OUT="${1:-pengram-release.keystore}"
ALIAS="${2:-pengram}"

if [ -f "$OUT" ]; then
  echo "Файл $OUT уже существует. Удалите его или укажите другое имя." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "keytool не найден. Поставьте JDK 17: sudo apt install openjdk-17-jdk-headless" >&2
  exit 1
fi

echo "Придумайте пароль хранилища (минимум 6 символов). Его нельзя терять."
read -r -s -p "Пароль: " PASS; echo
read -r -s -p "Ещё раз: " PASS2; echo
if [ "$PASS" != "$PASS2" ]; then
  echo "Пароли не совпали." >&2
  exit 1
fi

keytool -genkeypair \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Pengram, OU=Pengram, O=Pengram, L=-, ST=-, C=-"

echo
echo "Готово: $OUT"
echo "Отпечаток ключа:"
keytool -list -v -keystore "$OUT" -storepass "$PASS" -alias "$ALIAS" | grep -i "SHA256:" || true

echo
echo "=============================================================="
echo "Теперь добавьте 4 секрета: Settings -> Secrets and variables -> Actions"
echo
echo "  KEYSTORE_PASSWORD = $PASS"
echo "  KEY_ALIAS         = $ALIAS"
echo "  KEY_PASSWORD      = $PASS"
echo "  KEYSTORE_BASE64   = содержимое файла ниже"
echo
base64 -w 0 "$OUT" > "$OUT.base64"
echo "  (оно сохранено в $OUT.base64 — скопируйте одной строкой)"
echo
echo "ВАЖНО: сам $OUT положите в надёжное место и НЕ коммитьте в репозиторий."
echo "Потеряете ключ — обновления поверх беты ставиться перестанут."
echo "=============================================================="
