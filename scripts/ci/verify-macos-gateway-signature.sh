#!/usr/bin/env bash

set -euo pipefail

readonly expected_identifier="io.github.yearsyan.ohpi.gateway"
readonly expected_team_id="2XX5KZ6X3G"

check_notarization=true
if [[ "${1:-}" == "--signature-only" ]]; then
  check_notarization=false
  shift
fi

if [[ "$#" -eq 0 ]]; then
  echo "usage: verify-macos-gateway-signature.sh [--signature-only] <binary> [...]" >&2
  exit 2
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "macOS gateway signature verification must run on a Darwin host" >&2
  exit 1
fi

for binary in "$@"; do
  if [[ ! -f "$binary" ]]; then
    echo "macOS gateway binary does not exist: $binary" >&2
    exit 1
  fi

  verify_args=(--verify --strict --verbose=2)
  if [[ "$check_notarization" == true ]]; then
    verify_args+=(--check-notarization)
  fi
  /usr/bin/codesign "${verify_args[@]}" "$binary"

  signing_info="$(/usr/bin/codesign -d --verbose=4 "$binary" 2>&1)"
  requirements="$(/usr/bin/codesign -d -r- "$binary" 2>&1)"
  identifier="$(printf '%s\n' "$signing_info" | /usr/bin/sed -n 's/^Identifier=//p' | /usr/bin/head -n 1)"
  team_id="$(printf '%s\n' "$signing_info" | /usr/bin/sed -n 's/^TeamIdentifier=//p' | /usr/bin/head -n 1)"

  if [[ "$identifier" != "$expected_identifier" ]]; then
    echo "$binary: code identifier is '$identifier', expected '$expected_identifier'" >&2
    exit 1
  fi
  if [[ "$team_id" != "$expected_team_id" ]]; then
    echo "$binary: Team ID is '$team_id', expected '$expected_team_id'" >&2
    exit 1
  fi
  if ! /usr/bin/grep -Fq "Authority=Developer ID Application:" <<<"$signing_info"; then
    echo "$binary: signature is not backed by a Developer ID Application certificate" >&2
    exit 1
  fi
  if ! /usr/bin/grep -Eq '^CodeDirectory .*flags=.*\([^)]*runtime[^)]*\)' <<<"$signing_info"; then
    echo "$binary: Hardened Runtime is not enabled" >&2
    exit 1
  fi
  if ! /usr/bin/grep -Eq '^Timestamp=.+$' <<<"$signing_info"; then
    echo "$binary: trusted signing timestamp is missing" >&2
    exit 1
  fi

  if ! /usr/bin/grep -Fq "identifier \"$expected_identifier\"" <<<"$requirements"; then
    echo "$binary: designated requirement does not contain the fixed code identifier" >&2
    exit 1
  fi
  if ! /usr/bin/grep -Fq 'anchor apple generic' <<<"$requirements"; then
    echo "$binary: designated requirement is not anchored to Apple" >&2
    exit 1
  fi
  if ! /usr/bin/grep -Fq 'certificate leaf[field.1.2.840.113635.100.6.1.13]' <<<"$requirements"; then
    echo "$binary: designated requirement is not restricted to Developer ID Application" >&2
    exit 1
  fi
  if ! /usr/bin/grep -Fq "certificate leaf[subject.OU] = \"$expected_team_id\"" <<<"$requirements"; then
    echo "$binary: designated requirement does not pin the expected Team ID" >&2
    exit 1
  fi
  if /usr/bin/grep -Fq 'cdhash' <<<"$requirements"; then
    echo "$binary: designated requirement is tied to one binary hash" >&2
    exit 1
  fi

  echo "Verified macOS gateway signature: $binary ($expected_identifier, Team $expected_team_id)"
done
