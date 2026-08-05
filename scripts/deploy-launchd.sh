#!/bin/sh

set -eu

label=io.github.yearsyan.ohpi.gateway
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
template_path=$script_dir/launchd/$label.plist
launcher_source=$script_dir/launchd/ohpi-gateway-launch.sh

binary_dir=$HOME/.local/bin
binary_path=$binary_dir/ohpi-gateway
libexec_dir=$HOME/.local/libexec/ohpi-gateway
launcher_path=$libexec_dir/ohpi-gateway-launch
config_dir=$HOME/.config/ohpi
config_file=$config_dir/config.json
token_file=$config_dir/token
launch_agents_dir=$HOME/Library/LaunchAgents
plist_path=$launch_agents_dir/$label.plist
default_state_dir=$HOME/.local/state/ohpi
log_dir=$default_state_dir/log
stdout_log=$log_dir/ohpi-gateway.stdout.log
stderr_log=$log_dir/ohpi-gateway.stderr.log
uid=$(/usr/bin/id -u)
domain=gui/$uid
job_target=$domain/$label

note() {
	printf '==> %s\n' "$*"
}

die() {
	printf 'error: %s\n' "$*" >&2
	exit 1
}

require_command() {
	command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

plist_value() {
	key_path=$1
	if [ -f "$plist_path" ]; then
		/usr/bin/plutil -extract "$key_path" raw -o - "$plist_path" 2>/dev/null || true
	fi
}

config_value() {
	key=$1
	if [ ! -f "$config_file" ]; then
		return
	fi
	if ! value_type=$(/usr/bin/plutil -type "$key" "$config_file" 2>/dev/null); then
		return
	fi
	[ "$value_type" = string ] || die "$config_file: $key must be a string"
	value=$(/usr/bin/plutil -extract "$key" raw -o - "$config_file")
	[ -n "$value" ] || die "$config_file: $key must not be empty"
	printf '%s\n' "$value"
}

set_config_value() {
	key=$1
	value=$2
	if /usr/bin/plutil -type "$key" "$rendered_config_plist" >/dev/null 2>&1; then
		/usr/bin/plutil -replace "$key" -string "$value" "$rendered_config_plist"
	else
		/usr/bin/plutil -insert "$key" -string "$value" "$rendered_config_plist"
	fi
}

if [ "$(/usr/bin/uname -s)" != Darwin ]; then
	die "launchd deployment is supported only on macOS"
fi

require_command go
require_command launchctl
require_command plutil
require_command curl
require_command install
require_command mktemp

# One-shot migration from the former pi2ws install layout.
legacy_label=io.github.yearsyan.pi2ws
legacy_job_target=$domain/$legacy_label
legacy_plist_path=$launch_agents_dir/$legacy_label.plist
legacy_config_dir=$HOME/.config/pi2ws
legacy_config_file=$legacy_config_dir/config.json
legacy_token_file=$legacy_config_dir/token
legacy_state_dir=$HOME/.local/state/pi2ws
legacy_binary_path=$binary_dir/pi2ws
legacy_libexec_dir=$HOME/.local/libexec/pi2ws

migrate_legacy_session_files() {
	root=$1/sessions
	[ -d "$root" ] || return 0
	find "$root" -type f \( \
		-name 'pi2ws-session.json' -o \
		-name 'pi2ws-history.json' -o \
		-name 'pi2ws-replay.log' \
	\) -print 2>/dev/null | while IFS= read -r src; do
		base=$(basename "$src")
		dir=$(dirname "$src")
		case $base in
			pi2ws-session.json) dst=$dir/ohpi-session.json ;;
			pi2ws-history.json) dst=$dir/ohpi-history.json ;;
			pi2ws-replay.log) dst=$dir/ohpi-replay.log ;;
			*) continue ;;
		esac
		if [ -e "$dst" ]; then
			continue
		fi
		mv "$src" "$dst"
	done
}

if launchctl print "$legacy_job_target" >/dev/null 2>&1; then
	note "Stopping legacy LaunchAgent $legacy_label"
	launchctl bootout "$legacy_job_target" || true
fi
if [ -f "$legacy_plist_path" ]; then
	note "Removing legacy LaunchAgent plist"
	/bin/rm -f "$legacy_plist_path"
fi

if [ ! -d "$config_dir" ] && [ -d "$legacy_config_dir" ]; then
	note "Migrating config directory $legacy_config_dir -> $config_dir"
	/usr/bin/install -d -m 0700 "$config_dir"
	if [ -f "$legacy_config_file" ] && [ ! -f "$config_file" ]; then
		# Rewrite PI2WS_* keys to OHPI_* and default state path.
		/usr/bin/python3 - "$legacy_config_file" "$config_file" "$default_state_dir" <<'PY'
import json, sys
src, dst, default_state = sys.argv[1:4]
with open(src, encoding="utf-8") as f:
    data = json.load(f)
out = {}
for key, value in data.items():
    if key.startswith("PI2WS_"):
        key = "OHPI_" + key[len("PI2WS_"):]
    if key == "OHPI_DATA_DIR" and isinstance(value, str) and value.rstrip("/").endswith("/.local/state/pi2ws"):
        value = default_state
    out[key] = value
with open(dst, "w", encoding="utf-8") as f:
    json.dump(out, f, indent=2, sort_keys=True)
    f.write("\n")
PY
		/bin/chmod 0600 "$config_file"
	fi
	if [ -f "$legacy_token_file" ] && [ ! -f "$token_file" ]; then
		/usr/bin/install -m 0600 "$legacy_token_file" "$token_file"
	fi
fi

if [ ! -d "$default_state_dir" ] && [ -d "$legacy_state_dir" ]; then
	note "Migrating state directory $legacy_state_dir -> $default_state_dir"
	mv "$legacy_state_dir" "$default_state_dir"
fi
migrate_legacy_session_files "$default_state_dir"
if [ -d "$legacy_state_dir" ]; then
	migrate_legacy_session_files "$legacy_state_dir"
fi

# Best-effort cleanup of superseded install artifacts.
[ -f "$legacy_binary_path" ] && /bin/rm -f "$legacy_binary_path"
[ -d "$legacy_libexec_dir" ] && /bin/rm -rf "$legacy_libexec_dir"

deploy_token=${OHPI_TOKEN-}
unset OHPI_TOKEN
if [ -n "$deploy_token" ]; then
	newline='
'
	case $deploy_token in
		*"$newline"*) die "OHPI_TOKEN must be a single line" ;;
	esac
elif [ ! -s "$token_file" ]; then
	die "OHPI_TOKEN is required for the first deployment"
fi

if [ -f "$config_file" ]; then
	/usr/bin/plutil -p "$config_file" >/dev/null || die "invalid JSON configuration: $config_file"
fi

existing_config_listen=$(config_value OHPI_LISTEN)
existing_listen=$(plist_value EnvironmentVariables.OHPI_LISTEN)
if [ -n "${OHPI_LISTEN-}" ]; then
	listen=$OHPI_LISTEN
elif [ -n "$existing_config_listen" ]; then
	listen=$existing_config_listen
elif [ -n "$existing_listen" ]; then
	listen=$existing_listen
else
	listen=127.0.0.1:18080
fi

existing_config_data_dir=$(config_value OHPI_DATA_DIR)
existing_data_dir=$(plist_value EnvironmentVariables.OHPI_DATA_DIR)
if [ -n "${OHPI_DATA_DIR-}" ]; then
	data_dir=$OHPI_DATA_DIR
elif [ -n "$existing_config_data_dir" ]; then
	data_dir=$existing_config_data_dir
elif [ -n "$existing_data_dir" ]; then
	data_dir=$existing_data_dir
else
	data_dir=$default_state_dir
fi

existing_config_work_dir=$(config_value OHPI_WORK_DIR)
existing_work_dir=$(plist_value EnvironmentVariables.OHPI_WORK_DIR)
if [ -n "${OHPI_WORK_DIR-}" ]; then
	work_dir=$OHPI_WORK_DIR
elif [ -n "$existing_config_work_dir" ]; then
	work_dir=$existing_config_work_dir
elif [ -n "$existing_work_dir" ]; then
	work_dir=$existing_work_dir
else
	work_dir=$repo_root
fi
[ -d "$work_dir" ] || die "OHPI_WORK_DIR is not a directory: $work_dir"

existing_config_title_model=$(config_value OHPI_TITLE_MODEL)
if [ -n "${OHPI_TITLE_MODEL-}" ]; then
	title_model=$OHPI_TITLE_MODEL
elif [ -n "$existing_config_title_model" ]; then
	title_model=$existing_config_title_model
else
	title_model=auto
fi

existing_pi_command=$(plist_value EnvironmentVariables.OHPI_PI_COMMAND)
if [ -n "${OHPI_PI_COMMAND-}" ]; then
	pi_command=$OHPI_PI_COMMAND
elif [ -n "$existing_pi_command" ] && [ -x "$existing_pi_command" ]; then
	pi_command=$existing_pi_command
else
	pi_command=$(command -v pi 2>/dev/null || true)
fi
[ -n "$pi_command" ] || die "pi was not found; set OHPI_PI_COMMAND to its absolute path"
[ -x "$pi_command" ] || die "pi is not executable: $pi_command"

# fnm exposes pi through a per-shell path. Prefer its stable Node installation
# so the LaunchAgent still starts after logout or reboot.
if command -v realpath >/dev/null 2>&1; then
	resolved_pi=$(realpath "$pi_command" 2>/dev/null || true)
	case $resolved_pi in
		*/installation/lib/node_modules/*)
			fnm_installation=${resolved_pi%%/lib/node_modules/*}
			if [ -x "$fnm_installation/bin/pi" ]; then
				pi_command=$fnm_installation/bin/pi
			fi
			;;
	esac
fi

pi_dir=$(dirname -- "$pi_command")
if [ -n "${OHPI_LAUNCH_PATH-}" ]; then
	launch_path=$OHPI_LAUNCH_PATH
else
	launch_path=$binary_dir:$pi_dir:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
fi

listen_port=${listen##*:}
case $listen_port in
	''|*[!0-9]*) die "cannot derive health-check port from OHPI_LISTEN=$listen" ;;
esac
health_url=${OHPI_HEALTH_URL-http://127.0.0.1:$listen_port/healthz}

deploy_tmp=$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/ohpi-deploy.XXXXXX")
cleanup() {
	/bin/rm -rf "$deploy_tmp"
}
trap cleanup EXIT HUP INT TERM

built_binary=$deploy_tmp/ohpi-gateway
rendered_plist=$deploy_tmp/$label.plist
rendered_config=$deploy_tmp/config.json
rendered_config_plist=$deploy_tmp/config.plist
token_source=$deploy_tmp/token

note "Building production binary"
(
	cd "$repo_root"
	CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o "$built_binary" ./cmd/ohpi-gateway
)

/usr/bin/install -m 0600 "$template_path" "$rendered_plist"
/usr/bin/plutil -remove ProgramArguments.0 "$rendered_plist"
/usr/bin/plutil -insert ProgramArguments.0 -string "$launcher_path" "$rendered_plist"
/usr/bin/plutil -replace EnvironmentVariables.PATH -string "$launch_path" "$rendered_plist"
/usr/bin/plutil -replace EnvironmentVariables.OHPI_BINARY -string "$binary_path" "$rendered_plist"
/usr/bin/plutil -replace EnvironmentVariables.OHPI_CONFIG_FILE -string "$config_file" "$rendered_plist"
/usr/bin/plutil -replace EnvironmentVariables.OHPI_PI_COMMAND -string "$pi_command" "$rendered_plist"
/usr/bin/plutil -replace EnvironmentVariables.OHPI_TOKEN_FILE -string "$token_file" "$rendered_plist"
/usr/bin/plutil -replace WorkingDirectory -string "$work_dir" "$rendered_plist"
/usr/bin/plutil -replace StandardOutPath -string "$stdout_log" "$rendered_plist"
/usr/bin/plutil -replace StandardErrorPath -string "$stderr_log" "$rendered_plist"
/usr/bin/plutil -lint "$rendered_plist" >/dev/null

if [ -f "$config_file" ]; then
	/usr/bin/plutil -convert xml1 -o "$rendered_config_plist" "$config_file"
else
	/usr/bin/plutil -create xml1 "$rendered_config_plist"
fi
set_config_value OHPI_LISTEN "$listen"
set_config_value OHPI_DATA_DIR "$data_dir"
set_config_value OHPI_WORK_DIR "$work_dir"
set_config_value OHPI_TITLE_MODEL "$title_model"
/usr/bin/plutil -convert json -r -o "$rendered_config" "$rendered_config_plist"
/usr/bin/plutil -p "$rendered_config" >/dev/null

note "Preparing installation directories"
/usr/bin/install -d -m 0755 "$binary_dir" "$libexec_dir" "$launch_agents_dir"
/usr/bin/install -d -m 0700 "$config_dir" "$data_dir" "$log_dir"

if [ -n "$deploy_token" ]; then
	umask 077
	printf '%s\n' "$deploy_token" >"$token_source"
	/usr/bin/install -m 0600 "$token_source" "$token_file"
fi
unset deploy_token

/usr/bin/install -m 0600 "$rendered_config" "$config_file"

if launchctl print "$job_target" >/dev/null 2>&1; then
	note "Stopping existing LaunchAgent"
	launchctl bootout "$job_target"
fi

note "Installing binary and LaunchAgent"
/usr/bin/install -m 0755 "$built_binary" "$binary_path"
/usr/bin/install -m 0755 "$launcher_source" "$launcher_path"
/usr/bin/install -m 0644 "$rendered_plist" "$plist_path"

launchctl enable "$job_target"
bootstrap_attempt=1
while ! launchctl bootstrap "$domain" "$plist_path"; do
	if [ "$bootstrap_attempt" -ge 5 ]; then
		die "failed to bootstrap $label after $bootstrap_attempt attempts"
	fi
	bootstrap_attempt=$((bootstrap_attempt + 1))
	note "LaunchAgent bootstrap not ready; retrying ($bootstrap_attempt/5)"
	sleep 1
done
job_pid=$(launchctl kickstart -kp "$job_target")

note "Waiting for $health_url"
healthy=false
attempt=0
while [ "$attempt" -lt 40 ]; do
	if /usr/bin/curl -fsS --max-time 1 "$health_url" >/dev/null 2>&1; then
		healthy=true
		break
	fi
	attempt=$((attempt + 1))
	/bin/sleep 0.25
done

if [ "$healthy" != true ] || ! /bin/kill -0 "$job_pid" 2>/dev/null; then
	printf 'error: LaunchAgent did not become healthy\n' >&2
	launchctl print "$job_target" >&2 || true
	if [ -f "$stderr_log" ]; then
		printf '%s\n' '--- recent stderr ---' >&2
		/usr/bin/tail -n 40 "$stderr_log" >&2 || true
	fi
	exit 1
fi

note "Deployment complete"
printf 'Binary:      %s\n' "$binary_path"
printf 'LaunchAgent: %s\n' "$plist_path"
printf 'Config:      %s\n' "$config_file"
printf 'PID:         %s\n' "$job_pid"
printf 'Health:      %s\n' "$health_url"
printf 'Logs:        %s\n' "$log_dir"
