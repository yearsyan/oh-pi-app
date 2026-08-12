#!/usr/bin/env bash

set -euo pipefail

release_dir="${1:?usage: sign-notarize-macos.sh <release-dir> <version>}"
version="${2:?usage: sign-notarize-macos.sh <release-dir> <version>}"
script_dir="$(cd -- "$(dirname -- "$0")" && pwd -P)"
verify_signature_script="${script_dir}/verify-macos-gateway-signature.sh"
readonly gateway_code_identifier="io.github.yearsyan.ohpi.gateway"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS signing must run on a Darwin host" >&2
  exit 1
fi
if [[ -z "$version" || "$version" == *[!0-9A-Za-z._+-]* ]]; then
  echo "invalid macOS release version: ${version}" >&2
  exit 1
fi

required_secrets=(
  MACOS_CERTIFICATE_PASSWORD
  APPLE_NOTARY_KEY_ID
  APPLE_NOTARY_ISSUER_ID
)

for secret_name in "${required_secrets[@]}"; do
  if [[ -z "${!secret_name:-}" ]]; then
    echo "required signing secret is missing: ${secret_name}" >&2
    exit 1
  fi
done

if [[ -z "${MACOS_CERTIFICATE_P12_PATH:-}" && -z "${MACOS_CERTIFICATE_P12_BASE64:-}" ]]; then
  echo "set MACOS_CERTIFICATE_P12_PATH or MACOS_CERTIFICATE_P12_BASE64" >&2
  exit 1
fi
if [[ -n "${MACOS_CERTIFICATE_P12_PATH:-}" && ! -f "$MACOS_CERTIFICATE_P12_PATH" ]]; then
  echo "Developer ID PKCS#12 archive does not exist: ${MACOS_CERTIFICATE_P12_PATH}" >&2
  exit 1
fi
if [[ -z "${APPLE_NOTARY_KEY_P8_PATH:-}" && -z "${APPLE_NOTARY_KEY_P8_BASE64:-}" ]]; then
  echo "set APPLE_NOTARY_KEY_P8_PATH or APPLE_NOTARY_KEY_P8_BASE64" >&2
  exit 1
fi
if [[ -n "${APPLE_NOTARY_KEY_P8_PATH:-}" && ! -f "$APPLE_NOTARY_KEY_P8_PATH" ]]; then
  echo "App Store Connect API key does not exist: ${APPLE_NOTARY_KEY_P8_PATH}" >&2
  exit 1
fi

temporary_parent="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
if [[ ! -d "$temporary_parent" ]]; then
  echo "temporary directory does not exist: ${temporary_parent}" >&2
  exit 1
fi
temporary_root="$(/usr/bin/mktemp -d "${temporary_parent}/ohpi-macos-sign.XXXXXX")"

run_suffix="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"
certificate_path="${temporary_root}/ohpi-developer-id-${run_suffix}.p12"
notary_key_path="${temporary_root}/ohpi-notary-key-${run_suffix}.p8"
keychain_path="${temporary_root}/ohpi-signing-${run_suffix}.keychain-db"
payload_dir="${temporary_root}/ohpi-gateway-${version}-darwin"
archive_path="${release_dir}/ohpi-gateway-${version}-darwin.zip"
original_keychains=()
while IFS= read -r existing_keychain; do
  if [[ -n "$existing_keychain" ]]; then
    original_keychains+=("$existing_keychain")
  fi
done < <(/usr/bin/security list-keychains -d user | /usr/bin/sed 's/^[[:space:]]*"//; s/"$//')
keychain_added=false

cleanup() {
  if [[ "$keychain_added" == true && "${#original_keychains[@]}" -gt 0 ]]; then
    /usr/bin/security list-keychains -d user -s "${original_keychains[@]}" >/dev/null 2>&1 || true
  fi
  if [[ -f "$keychain_path" ]]; then
    /usr/bin/security delete-keychain "$keychain_path" >/dev/null 2>&1 || true
  fi
  /bin/rm -rf "$temporary_root"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$release_dir"
test ! -e "$archive_path"

if [[ -n "${MACOS_CERTIFICATE_P12_PATH:-}" ]]; then
  /bin/cp "$MACOS_CERTIFICATE_P12_PATH" "$certificate_path"
else
  printf '%s' "$MACOS_CERTIFICATE_P12_BASE64" | /usr/bin/base64 --decode > "$certificate_path"
fi
if [[ -n "${APPLE_NOTARY_KEY_P8_PATH:-}" ]]; then
  /bin/cp "$APPLE_NOTARY_KEY_P8_PATH" "$notary_key_path"
else
  printf '%s' "$APPLE_NOTARY_KEY_P8_BASE64" | /usr/bin/base64 --decode > "$notary_key_path"
fi
/bin/chmod 600 "$certificate_path" "$notary_key_path"

ci_keychain_password="$(/usr/bin/openssl rand -hex 32)"
/usr/bin/security create-keychain -p "$ci_keychain_password" "$keychain_path"
/usr/bin/security set-keychain-settings -lut 21600 "$keychain_path"
/usr/bin/security unlock-keychain -p "$ci_keychain_password" "$keychain_path"
/usr/bin/security import "$certificate_path" \
  -P "$MACOS_CERTIFICATE_PASSWORD" \
  -A -t cert -f pkcs12 -k "$keychain_path"
/usr/bin/security set-key-partition-list \
  -S apple-tool:,apple: \
  -s -k "$ci_keychain_password" "$keychain_path"
/usr/bin/security list-keychains -d user -s "$keychain_path" "${original_keychains[@]}"
keychain_added=true

identity_output="$(/usr/bin/security find-identity -v -p codesigning "$keychain_path")"
signing_identity="$(printf '%s\n' "$identity_output" | /usr/bin/awk -F '"' '/Developer ID Application:/ { print $2; exit }')"
if [[ -z "$signing_identity" ]]; then
  echo "the PKCS#12 archive does not contain a Developer ID Application identity" >&2
  printf '%s\n' "$identity_output" >&2
  exit 1
fi

binaries=(
  "${release_dir}/ohpi-gateway-${version}-darwin-amd64"
  "${release_dir}/ohpi-gateway-${version}-darwin-arm64"
)

for binary in "${binaries[@]}"; do
  if [[ ! -f "$binary" ]]; then
    echo "missing macOS release binary: ${binary}" >&2
    exit 1
  fi

  /usr/bin/codesign \
    --force \
    --identifier "$gateway_code_identifier" \
    --sign "$signing_identity" \
    --options runtime \
    --timestamp \
    "$binary"
  /bin/bash "$verify_signature_script" --signature-only "$binary"
done

mkdir -p "$payload_dir"
for binary in "${binaries[@]}"; do
  /bin/cp "$binary" "$payload_dir/$(basename "$binary")"
done
/usr/bin/ditto -c -k --norsrc --keepParent "$payload_dir" "$archive_path"

/usr/bin/xcrun notarytool submit "$archive_path" \
  --key "$notary_key_path" \
  --key-id "$APPLE_NOTARY_KEY_ID" \
  --issuer "$APPLE_NOTARY_ISSUER_ID" \
  --wait \
  --timeout 30m

/bin/bash "$verify_signature_script" "${binaries[@]}"

echo "Signed and notarized ${archive_path} with ${signing_identity}"
