#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_ANDROID_SDK="$HOME/android-sdk"
ANDROID_SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$DEFAULT_ANDROID_SDK}}"

if [[ ! -d "$ANDROID_SDK_DIR" ]]; then
  cat >&2 <<MSG
❌ No se encontró el Android SDK en: $ANDROID_SDK_DIR

Define ANDROID_HOME o ANDROID_SDK_ROOT antes de compilar. Ejemplo:
export ANDROID_HOME="$DEFAULT_ANDROID_SDK"
MSG
  exit 1
fi

cd "$PROJECT_ROOT"

cat > local.properties <<EOF_LOCAL
sdk.dir=$ANDROID_SDK_DIR
EOF_LOCAL

echo "✅ local.properties creado:"
cat local.properties

if ! git check-ignore -q local.properties; then
  cat >&2 <<'MSG'
❌ local.properties no está protegido por .gitignore.
MSG
  exit 1
fi

echo "✅ local.properties protegido en .gitignore"

mapfile -t AAPT2_CANDIDATES < <(find "$ANDROID_SDK_DIR/build-tools" -maxdepth 2 -type f -name aapt2 2>/dev/null | sort -Vr)

if [[ ${#AAPT2_CANDIDATES[@]} -eq 0 ]]; then
  cat >&2 <<MSG
❌ No se encontró aapt2 dentro de $ANDROID_SDK_DIR/build-tools.
Instala Android build-tools en Termux antes de compilar.
MSG
  exit 1
fi

AAPT2_BIN="${AAPT2_CANDIDATES[0]}"
chmod +x "$AAPT2_BIN" 2>/dev/null || true

echo "✅ aapt2 Termux/Android seleccionado: $AAPT2_BIN"
echo "ℹ️  Se evita el aapt2 Linux descargado por Maven, incompatible con Termux/Android."

chmod +x ./gradlew 2>/dev/null || true
exec ./gradlew \
  -Pandroid.aapt2FromMavenOverride="$AAPT2_BIN" \
  :app:assembleDebug \
  "$@"
