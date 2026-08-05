#!/bin/sh

set -eu

umask 077

: "${OHPI_BINARY:?OHPI_BINARY is not set}"
: "${OHPI_TOKEN_FILE:?OHPI_TOKEN_FILE is not set}"

if [ ! -x "$OHPI_BINARY" ]; then
	printf 'ohpi-gateway launcher: binary is not executable: %s\n' "$OHPI_BINARY" >&2
	exit 78
fi
if [ ! -r "$OHPI_TOKEN_FILE" ]; then
	printf 'ohpi-gateway launcher: token file is not readable: %s\n' "$OHPI_TOKEN_FILE" >&2
	exit 78
fi

ohpi_token=
IFS= read -r ohpi_token <"$OHPI_TOKEN_FILE" || true
if [ -z "$ohpi_token" ]; then
	printf 'ohpi-gateway launcher: token file is empty: %s\n' "$OHPI_TOKEN_FILE" >&2
	exit 78
fi

OHPI_TOKEN=$ohpi_token
export OHPI_TOKEN
unset ohpi_token

exec "$OHPI_BINARY"
