#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "Usage: $0 <apk> <public-base-url> <version-name> <version-code> [release-notes]"
  echo "Example: $0 app-release.apk https://example.com/qingzhang 1.1.1 3 'Fix import issues'"
  exit 1
fi

APK="$1"
BASE_URL="${2%/}"
VERSION_NAME="$3"
VERSION_CODE="$4"
NOTES="${5:-}"
PACKAGE_NAME="com.example.qingzhang"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$SCRIPT_DIR/public"

[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
[[ "$VERSION_NAME" =~ ^[0-9A-Za-z._-]{1,32}$ ]] || { echo "Invalid version name" >&2; exit 1; }
[[ "$VERSION_CODE" =~ ^0*([1-9][0-9]*)$ ]] || { echo "Invalid version code" >&2; exit 1; }
VERSION_CODE="${BASH_REMATCH[1]}"

SIZE=$(wc -c < "$APK" | tr -d ' ')
(( SIZE > 0 && SIZE <= 104857600 )) || { echo "APK size must be between 1 byte and 100 MiB" >&2; exit 1; }
FILENAME="qingzhang-${VERSION_NAME}.apk"
APK_URL="$BASE_URL/$FILENAME"
python3 - "$BASE_URL" "$APK_URL" <<'PY'
import sys
from urllib.parse import urlsplit

base, apk_url = sys.argv[1:]
try:
    url = urlsplit(base)
    port = url.port
    valid = (url.scheme == "https" and url.hostname and url.username is None
             and not any(c.isspace() or c in "?#\\" for c in base))
except ValueError:
    valid = False
if not valid or len(apk_url) > 2000:
    sys.exit("Public base URL must be HTTPS with a host and without credentials, query or fragment. APK URL limit is 2000 characters.")
PY

AAPT2=$(command -v aapt2 || true)
if [[ -z "$AAPT2" ]]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$SCRIPT_DIR/../.android-sdk"; do
    [[ -n "$sdk" ]] || continue
    for tool in "$sdk"/build-tools/*/aapt2 "$sdk"/build-tools/*/aapt2.exe; do
      if [[ -x "$tool" ]]; then AAPT2="$tool"; fi
    done
    [[ -z "$AAPT2" ]] || break
  done
fi
[[ -n "$AAPT2" ]] || { echo "Install Android SDK Build Tools and put aapt2 on PATH" >&2; exit 1; }

BADGING=$("$AAPT2" dump badging "$APK")
PACKAGE_PATTERN="^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'"
if [[ ! "$BADGING" =~ $PACKAGE_PATTERN ]] ||
   [[ "${BASH_REMATCH[1]}" != "$PACKAGE_NAME" || "${BASH_REMATCH[2]}" != "$VERSION_CODE" || "${BASH_REMATCH[3]}" != "$VERSION_NAME" ]]; then
  echo "APK package name, version code or version name does not match the publishing arguments" >&2
  exit 1
fi

HASH=$(sha256sum "$APK" | awk '{print $1}')
mkdir -p "$OUT_DIR"
cp "$APK" "$OUT_DIR/$FILENAME"

python3 - "$OUT_DIR/latest.json" "$PACKAGE_NAME" "$VERSION_CODE" "$VERSION_NAME" \
  "$APK_URL" "$HASH" "$SIZE" "$NOTES" <<'PY'
import json
import sys

path, package, code, name, apk_url, digest, size, notes = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump({
        "packageName": package,
        "versionCode": int(code),
        "versionName": name,
        "apkUrl": apk_url,
        "sha256": digest,
        "size": int(size),
        "releaseNotes": notes,
    }, handle, ensure_ascii=False, indent=2)
PY

echo "Generated: $OUT_DIR/latest.json"
echo "SHA-256: $HASH"
echo "Update URL: $BASE_URL/latest.json"
