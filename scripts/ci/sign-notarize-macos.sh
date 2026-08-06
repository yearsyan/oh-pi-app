#!/usr/bin/env bash

set -euo pipefail

release_dir="${1:?usage: sign-notarize-macos.sh <release-dir> <version>}"
version="${2:?usage: sign-notarize-macos.sh <release-dir> <version>}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS signing must run on a Darwin host" >&2
  exit 1
fi

required_secrets=(
  MACOS_CERTIFICATE_P12_BASE64
  MACOS_CERTIFICATE_PASSWORD
  APPLE_NOTARY_KEY_P8_BASE64
  APPLE_NOTARY_KEY_ID
  APPLE_NOTARY_ISSUER_ID
)

for secret_name in "${required_secrets[@]}"; do
  if [[ -z "${!secret_name:-}" ]]; then
    echo "required signing secret is missing: ${secret_name}" >&2
    exit 1
  fi
done

if [[ -z "${RUNNER_TEMP:-}" ]]; then
  echo "RUNNER_TEMP is required" >&2
  exit 1
fi

run_suffix="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"
certificate_path="${RUNNER_TEMP}/ohpi-developer-id-${run_suffix}.p12"
notary_key_path="${RUNNER_TEMP}/ohpi-notary-key-${run_suffix}.p8"
keychain_path="${RUNNER_TEMP}/ohpi-signing-${run_suffix}.keychain-db"
payload_dir="${RUNNER_TEMP}/ohpi-gateway-${version}-darwin"
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
  /bin/rm -f "$certificate_path" "$notary_key_path"
  /bin/rm -rf "$payload_dir"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$release_dir"
test ! -e "$archive_path"

printf '%s' "$MACOS_CERTIFICATE_P12_BASE64" | /usr/bin/base64 --decode > "$certificate_path"
printf '%s' "$APPLE_NOTARY_KEY_P8_BASE64" | /usr/bin/base64 --decode > "$notary_key_path"
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
    --sign "$signing_identity" \
    --options runtime \
    --timestamp \
    "$binary"
  /usr/bin/codesign --verify --strict --verbose=2 "$binary"
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

for binary in "${binaries[@]}"; do
  /usr/bin/codesign \
    --verify \
    --strict \
    --verbose=2 \
    --check-notarization \
    "$binary"
done

echo "Signed and notarized ${archive_path} with ${signing_identity}"
