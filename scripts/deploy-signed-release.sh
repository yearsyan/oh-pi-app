#!/bin/sh

set -eu

usage() {
	cat <<'EOF'
Usage: deploy-signed-release.sh <check|deploy> [--version VERSION]

Downloads an official macOS gateway release, verifies its SHA-256,
Developer ID identity, fixed designated requirement, and notarization.
The deploy command then installs it through scripts/deploy-launchd.sh.
EOF
}

die() {
	printf 'error: %s\n' "$*" >&2
	exit 1
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

case ${1-} in
	check|deploy) action=$1; shift ;;
	-h|--help) usage; exit 0 ;;
	*) usage >&2; exit 2 ;;
esac

version=${OHPI_RELEASE_VERSION-}
while [ "$#" -gt 0 ]; do
	case $1 in
		--version)
			[ "$#" -ge 2 ] || die "--version requires a value"
			version=$2
			shift 2
			;;
		-h|--help)
			usage
			exit 0
			;;
		*) die "unknown argument: $1" ;;
	esac
done

[ "$(/usr/bin/uname -s)" = Darwin ] || die "signed local deployment is supported only on macOS"
require_command curl
require_command mktemp
require_command plutil
require_command shasum

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
verifier=$script_dir/ci/verify-macos-gateway-signature.sh
deployer=$script_dir/deploy-launchd.sh
[ -f "$verifier" ] || die "signature verifier not found: $verifier"
[ -f "$deployer" ] || die "LaunchAgent deployer not found: $deployer"

case $(/usr/bin/uname -m) in
	arm64) release_arch=arm64 ;;
	x86_64) release_arch=amd64 ;;
	*) die "unsupported macOS architecture: $(/usr/bin/uname -m)" ;;
esac

download_dir=$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/ohpi-signed-deploy.XXXXXX")
cleanup() {
	/bin/rm -rf "$download_dir"
}
trap cleanup EXIT HUP INT TERM

if [ -z "$version" ]; then
	release_json=$download_dir/release.json
	/usr/bin/curl -fsSL --retry 3 \
		https://api.github.com/repos/yearsyan/oh-pi-app/releases/latest \
		-o "$release_json"
	version=$(/usr/bin/plutil -extract tag_name raw -o - "$release_json")
	version=${version#v}
fi
case $version in
	''|*[!0-9A-Za-z._+-]*) die "invalid release version: $version" ;;
esac

asset_name=ohpi-gateway-$version-darwin-$release_arch
release_base=https://github.com/yearsyan/oh-pi-app/releases/download/v$version
binary=$download_dir/$asset_name
checksums=$download_dir/SHA256SUMS.txt

printf 'Downloading Oh Pi gateway %s for darwin/%s\n' "$version" "$release_arch"
/usr/bin/curl -fsSL --retry 3 "$release_base/$asset_name" -o "$binary"
/usr/bin/curl -fsSL --retry 3 "$release_base/SHA256SUMS.txt" -o "$checksums"

expected_hash=$(/usr/bin/awk -v name="$asset_name" '$2 == name { print $1; exit }' "$checksums")
[ -n "$expected_hash" ] || die "$asset_name is missing from SHA256SUMS.txt"
actual_hash=$(/usr/bin/shasum -a 256 "$binary" | /usr/bin/awk '{ print $1 }')
[ "$actual_hash" = "$expected_hash" ] || die "SHA-256 mismatch for $asset_name"

/bin/bash "$verifier" "$binary"
if [ "$action" = check ]; then
	printf 'Verified signed release %s (%s)\n' "$version" "$actual_hash"
	exit 0
fi

OHPI_BINARY=$binary OHPI_VERSION=$version /bin/sh "$deployer"
