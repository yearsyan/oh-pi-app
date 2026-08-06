#!/usr/bin/env bash

set -euo pipefail

version="${1:?usage: upload-testflight.sh <version> <build-number>}"
build_number="${2:?usage: upload-testflight.sh <version> <build-number>}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "TestFlight upload must run on a Darwin host" >&2
  exit 1
fi

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "version must use major.minor.patch: ${version}" >&2
  exit 1
fi

if [[ ! "$build_number" =~ ^[0-9]+\.[0-9]+$ ]]; then
  echo "build number must contain two numeric components: ${build_number}" >&2
  exit 1
fi

required_secrets=(
  IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64
  IOS_DISTRIBUTION_CERTIFICATE_PASSWORD
  IOS_APP_STORE_PROFILE_BASE64
  APPLE_NOTARY_KEY_P8_BASE64
  APPLE_NOTARY_KEY_ID
  APPLE_NOTARY_ISSUER_ID
)

for secret_name in "${required_secrets[@]}"; do
  if [[ -z "${!secret_name:-}" ]]; then
    echo "required TestFlight CI secret is missing: ${secret_name}" >&2
    exit 1
  fi
done

if [[ -z "${RUNNER_TEMP:-}" ]]; then
  echo "RUNNER_TEMP is required" >&2
  exit 1
fi

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
project_path="${repo_dir}/app/iosApp/iosApp.xcodeproj"
export_options_path="${repo_dir}/app/iosApp/Configuration/ExportOptions-TestFlight.plist"
work_dir="$(mktemp -d "${RUNNER_TEMP}/ohpi-testflight.XXXXXX")"
archive_path="${work_dir}/OhPiApp.xcarchive"
export_path="${work_dir}/export"
api_key_path="${work_dir}/AuthKey_${APPLE_NOTARY_KEY_ID}.p8"
certificate_path="${work_dir}/apple-distribution.p12"
profile_source_path="${work_dir}/OhPiApp.mobileprovision"
profile_plist_path="${work_dir}/profile.plist"
keychain_path="${work_dir}/testflight-signing.keychain-db"
profile_install_path=""
profile_installed=false
keychain_added=false
original_keychains=()

while IFS= read -r existing_keychain; do
  if [[ -n "$existing_keychain" ]]; then
    original_keychains+=("$existing_keychain")
  fi
done < <(/usr/bin/security list-keychains -d user | /usr/bin/sed 's/^[[:space:]]*"//; s/"$//')

cleanup() {
  if [[ "$keychain_added" == true && "${#original_keychains[@]}" -gt 0 ]]; then
    /usr/bin/security list-keychains -d user -s "${original_keychains[@]}" >/dev/null 2>&1 || true
  fi
  if [[ -f "$keychain_path" ]]; then
    /usr/bin/security delete-keychain "$keychain_path" >/dev/null 2>&1 || true
  fi
  if [[ "$profile_installed" == true && -n "$profile_install_path" ]]; then
    /bin/rm -f "$profile_install_path"
  fi
  /bin/rm -rf "$work_dir"
}
trap cleanup EXIT HUP INT TERM

printf '%s' "$IOS_DISTRIBUTION_CERTIFICATE_P12_BASE64" | /usr/bin/base64 --decode > "$certificate_path"
printf '%s' "$IOS_APP_STORE_PROFILE_BASE64" | /usr/bin/base64 --decode > "$profile_source_path"
printf '%s' "$APPLE_NOTARY_KEY_P8_BASE64" | /usr/bin/base64 --decode > "$api_key_path"
/bin/chmod 600 "$certificate_path" "$profile_source_path" "$api_key_path"

/usr/bin/security cms -D -i "$profile_source_path" -o "$profile_plist_path"
profile_name="$(/usr/libexec/PlistBuddy -c 'Print :Name' "$profile_plist_path")"
profile_uuid="$(/usr/libexec/PlistBuddy -c 'Print :UUID' "$profile_plist_path")"
profile_team="$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' "$profile_plist_path")"
profile_app_id="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' "$profile_plist_path")"
profile_debuggable="$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:get-task-allow' "$profile_plist_path")"

if [[ "$profile_name" != "Oh Pi TestFlight CI App Store" ]]; then
  echo "unexpected provisioning profile name: ${profile_name}" >&2
  exit 1
fi
if [[ ! "$profile_uuid" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]]; then
  echo "unexpected provisioning profile UUID: ${profile_uuid}" >&2
  exit 1
fi
if [[ "$profile_team" != "2XX5KZ6X3G" ]]; then
  echo "unexpected provisioning profile team: ${profile_team}" >&2
  exit 1
fi
if [[ "$profile_app_id" != "2XX5KZ6X3G.io.github.yearsyan.ohpi.OhPiApp" ]]; then
  echo "unexpected provisioning profile app identifier: ${profile_app_id}" >&2
  exit 1
fi
if [[ "$profile_debuggable" != "false" ]]; then
  echo "provisioning profile unexpectedly enables get-task-allow" >&2
  exit 1
fi

profile_directory="${HOME}/Library/MobileDevice/Provisioning Profiles"
profile_install_path="${profile_directory}/${profile_uuid}.mobileprovision"
/bin/mkdir -p "$profile_directory"
if [[ -e "$profile_install_path" ]]; then
  if ! /usr/bin/cmp -s "$profile_source_path" "$profile_install_path"; then
    echo "a different provisioning profile already uses UUID ${profile_uuid}" >&2
    exit 1
  fi
else
  /bin/cp "$profile_source_path" "$profile_install_path"
  profile_installed=true
fi

ci_keychain_password="$(/usr/bin/openssl rand -hex 32)"
/usr/bin/security create-keychain -p "$ci_keychain_password" "$keychain_path"
/usr/bin/security set-keychain-settings -lut 21600 "$keychain_path"
/usr/bin/security unlock-keychain -p "$ci_keychain_password" "$keychain_path"
/usr/bin/security import "$certificate_path" \
  -P "$IOS_DISTRIBUTION_CERTIFICATE_PASSWORD" \
  -A -t cert -f pkcs12 -k "$keychain_path"
/usr/bin/security set-key-partition-list \
  -S apple-tool:,apple: \
  -s -k "$ci_keychain_password" "$keychain_path"
/usr/bin/security list-keychains -d user -s "$keychain_path" "${original_keychains[@]}"
keychain_added=true

identity_output="$(/usr/bin/security find-identity -v -p codesigning "$keychain_path")"
signing_identity="$(printf '%s\n' "$identity_output" | /usr/bin/awk -F '"' '/Apple Distribution:/ { print $2; exit }')"
if [[ -z "$signing_identity" ]]; then
  echo "the PKCS#12 archive does not contain an Apple Distribution identity" >&2
  printf '%s\n' "$identity_output" >&2
  exit 1
fi

/usr/bin/xcodebuild \
  -project "$project_path" \
  -scheme iosApp \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath "$archive_path" \
  MARKETING_VERSION="$version" \
  CURRENT_PROJECT_VERSION="$build_number" \
  CODE_SIGNING_ALLOWED=NO \
  archive

app_path="$(/usr/bin/find "${archive_path}/Products/Applications" -maxdepth 1 -type d -name '*.app' -print -quit)"
if [[ -z "$app_path" ]]; then
  echo "archive does not contain an iOS app" >&2
  exit 1
fi

info_plist="${app_path}/Info.plist"
bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$info_plist")"
archive_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$info_plist")"
archive_build="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$info_plist")"

if [[ "$bundle_id" != "io.github.yearsyan.ohpi.OhPiApp" ]]; then
  echo "unexpected iOS bundle identifier: ${bundle_id}" >&2
  exit 1
fi
if [[ "$archive_version" != "$version" ]]; then
  echo "unexpected iOS marketing version: ${archive_version}" >&2
  exit 1
fi
if [[ "$archive_build" != "$build_number" ]]; then
  echo "unexpected iOS build number: ${archive_build}" >&2
  exit 1
fi

echo "Uploading ${bundle_id} ${archive_version} (${archive_build}) to App Store Connect"

/usr/bin/xcodebuild \
  -exportArchive \
  -archivePath "$archive_path" \
  -exportPath "$export_path" \
  -exportOptionsPlist "$export_options_path" \
  -authenticationKeyPath "$api_key_path" \
  -authenticationKeyID "$APPLE_NOTARY_KEY_ID" \
  -authenticationKeyIssuerID "$APPLE_NOTARY_ISSUER_ID"

echo "Apple accepted the TestFlight upload for ${bundle_id} ${archive_version} (${archive_build}), signed by ${signing_identity}"
