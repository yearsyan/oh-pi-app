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
  APPLE_NOTARY_KEY_P8_BASE64
  APPLE_NOTARY_KEY_ID
  APPLE_NOTARY_ISSUER_ID
)

for secret_name in "${required_secrets[@]}"; do
  if [[ -z "${!secret_name:-}" ]]; then
    echo "required App Store Connect secret is missing: ${secret_name}" >&2
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

cleanup() {
  /bin/rm -rf "$work_dir"
}
trap cleanup EXIT HUP INT TERM

printf '%s' "$APPLE_NOTARY_KEY_P8_BASE64" | /usr/bin/base64 --decode > "$api_key_path"
/bin/chmod 600 "$api_key_path"

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
  -allowProvisioningUpdates \
  -authenticationKeyPath "$api_key_path" \
  -authenticationKeyID "$APPLE_NOTARY_KEY_ID" \
  -authenticationKeyIssuerID "$APPLE_NOTARY_ISSUER_ID"

echo "Apple accepted the TestFlight upload for ${bundle_id} ${archive_version} (${archive_build})"
