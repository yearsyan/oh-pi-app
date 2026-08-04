#!/bin/sh

set -eu

umask 077

: "${PI2WS_BINARY:?PI2WS_BINARY is not set}"
: "${PI2WS_TOKEN_FILE:?PI2WS_TOKEN_FILE is not set}"

if [ ! -x "$PI2WS_BINARY" ]; then
	printf 'pi2ws launcher: binary is not executable: %s\n' "$PI2WS_BINARY" >&2
	exit 78
fi
if [ ! -r "$PI2WS_TOKEN_FILE" ]; then
	printf 'pi2ws launcher: token file is not readable: %s\n' "$PI2WS_TOKEN_FILE" >&2
	exit 78
fi

pi2ws_token=
IFS= read -r pi2ws_token <"$PI2WS_TOKEN_FILE" || true
if [ -z "$pi2ws_token" ]; then
	printf 'pi2ws launcher: token file is empty: %s\n' "$PI2WS_TOKEN_FILE" >&2
	exit 78
fi

PI2WS_TOKEN=$pi2ws_token
export PI2WS_TOKEN
unset pi2ws_token

exec "$PI2WS_BINARY"
