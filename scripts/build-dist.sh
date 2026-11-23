#!/usr/bin/env bash
#
# Build both release flavours and sign them exactly like the APKs already in
# app/build/dist:
#
#   reYAMF2-<version>-vector.apk (+ .idsig)
#   reYAMF2-<version>-legacy.apk (+ .idsig)
#
# Signing details are read (CLI flag > environment > ./keystore.properties):
#   --keystore / REYAMF_KEYSTORE          keystore file (default keystore/reyamf2-release.jks)
#   --store-password / REYAMF_STORE_PASSWORD
#   --alias / REYAMF_KEY_ALIAS            key alias (default reyamf2)
#   --key-password / REYAMF_KEY_PASSWORD  defaults to the store password
#
# keystore.properties is git-ignored; see keystore.properties.example.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# --- signing options -------------------------------------------------------
KEYSTORE="${REYAMF_KEYSTORE:-}"
STOREPASS="${REYAMF_STORE_PASSWORD:-}"
ALIAS="${REYAMF_KEY_ALIAS:-}"
KEYPASS="${REYAMF_KEY_PASSWORD:-}"

while [ $# -gt 0 ]; do
    case "$1" in
        --keystore)       KEYSTORE="$2"; shift 2 ;;
        --store-password) STOREPASS="$2"; shift 2 ;;
        --alias)          ALIAS="$2"; shift 2 ;;
        --key-password)   KEYPASS="$2"; shift 2 ;;
        -h|--help)        sed -n '2,18p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

PROPS="keystore.properties"
prop() {
    [ -f "$PROPS" ] || return 0
    sed -nE "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*(.*)$/\1/p" "$PROPS" | tail -1 | tr -d '\r'
}
[ -n "$KEYSTORE" ] || KEYSTORE="$(prop storeFile)"
[ -n "$STOREPASS" ] || STOREPASS="$(prop storePassword)"
[ -n "$ALIAS" ]     || ALIAS="$(prop keyAlias)"
[ -n "$KEYPASS" ]   || KEYPASS="$(prop keyPassword)"

[ -n "$KEYSTORE" ] || KEYSTORE="keystore/reyamf2-release.jks"
case "$KEYSTORE" in
    /*|[A-Za-z]:*) : ;;                 # already absolute
    *) KEYSTORE="$ROOT/$KEYSTORE" ;;
esac
[ -n "$ALIAS" ]   || ALIAS="reyamf2"
[ -n "$KEYPASS" ] || KEYPASS="$STOREPASS"

if [ ! -f "$KEYSTORE" ]; then
    echo "Keystore not found: $KEYSTORE" >&2
    echo "Place it there, or set REYAMF_KEYSTORE / storeFile in keystore.properties." >&2
    exit 1
fi
if [ -z "$STOREPASS" ]; then
    echo "Missing keystore password. Set REYAMF_STORE_PASSWORD or storePassword in keystore.properties." >&2
    exit 1
fi

# --- locate the Android SDK and build-tools -------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then
    SDK="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1 | tr -d '\r' | tr -d '\\')"
fi
if [ -z "$SDK" ]; then
    for d in "$HOME/AppData/Local/Android/Sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
        [ -d "$d" ] && SDK="$d" && break
    done
fi
if [ -z "$SDK" ] || [ ! -d "$SDK/build-tools" ]; then
    echo "Android SDK build-tools not found (set ANDROID_SDK_ROOT)." >&2
    exit 1
fi

WIN=0
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) WIN=1 ;; esac

BT="$(ls -1 "$SDK/build-tools" | sort -V | tail -1)"
if [ "$WIN" = 1 ]; then
    APKSIGNER="$SDK/build-tools/$BT/apksigner.bat"
    ZIPALIGN="$SDK/build-tools/$BT/zipalign.exe"
else
    APKSIGNER="$SDK/build-tools/$BT/apksigner"
    ZIPALIGN="$SDK/build-tools/$BT/zipalign"
fi
[ -f "$APKSIGNER" ] || { echo "apksigner not found under build-tools/$BT" >&2; exit 1; }
[ -f "$ZIPALIGN" ]  || { echo "zipalign not found under build-tools/$BT" >&2; exit 1; }

# --- version (kept in sync with baseVersionName in app/build.gradle.kts) --
VERSION="$(sed -nE 's/.*baseVersionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -1)"
[ -n "$VERSION" ] || VERSION="$(date +%Y%m%d)"

# --- build ----------------------------------------------------------------
echo ">> Building release APKs (version $VERSION)"
./gradlew --no-daemon :app:assembleVectorRelease :app:assembleLegacyRelease

DIST="$ROOT/app/build/dist"
mkdir -p "$DIST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for FLAVOR in vector legacy; do
    OUTDIR="app/build/outputs/apk/$FLAVOR/release"
    SRC="$OUTDIR/app-$FLAVOR-release-unsigned.apk"
    [ -f "$SRC" ] || SRC="$OUTDIR/app-$FLAVOR-release.apk"
    [ -f "$SRC" ] || { echo "Missing $FLAVOR release APK in $OUTDIR" >&2; exit 1; }

    ALIGNED="$TMP/$FLAVOR-aligned.apk"
    OUT="$DIST/reYAMF2-$VERSION-$FLAVOR.apk"

    echo ">> Signing $FLAVOR"
    "$ZIPALIGN" -p -f 4 "$SRC" "$ALIGNED"

    # v3 + v4 only, matching the existing dist APKs (and their .idsig files).
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
        --ks-pass "pass:$STOREPASS" --key-pass "pass:$KEYPASS" \
        --v1-signing-enabled false --v2-signing-enabled false \
        --v3-signing-enabled true --v4-signing-enabled true \
        --out "$OUT" "$ALIGNED"

    "$APKSIGNER" verify --print-certs "$OUT" >/dev/null
    echo "   $OUT"
    [ -f "$OUT.idsig" ] && echo "   $OUT.idsig"
done

echo ">> Done. Signed APKs are in $DIST"
