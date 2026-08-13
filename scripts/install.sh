#!/bin/sh
#
# ohpi-gateway 一键安装脚本（macOS / Linux，无需 TLS）
#
#   curl -fsSL https://raw.githubusercontent.com/yearsyan/oh-pi-app/main/scripts/install.sh | sh
#
# 流程：
#   1. 探测系统与架构，从 GitHub Release 下载对应平台的最新网关二进制并校验 SHA-256；
#   2. 生成 token（~/.config/oh-pi-app/token，0600）与配置文件（config.json，仅首次写入）；
#   3. 安装到 ~/.local/bin/ohpi-gateway；
#   4. 注册用户级服务并启动：macOS LaunchAgent、Linux systemd user，
#      无 systemd 时回退 nohup + PID 文件；
#   5. 轮询 /healthz 验证服务可用。
#
# 未检测到 pi 时自动安装 Node.js 22 与 pi（~/.local/bin/pi）：优先复用系统已兼容的
# Node，否则按官方 SHASUMS256.txt 校验后下载托管 Node 到 ~/.local/share/oh-pi-app/node/，
# 再以 npm --ignore-scripts 安装；全程不调用 sudo，也不修改 shell 启动文件。
# 设置 OHPI_NO_PI_INSTALL=1 可跳过自动安装（缺 pi 时直接报错）。
#
# 网关只监听 127.0.0.1:18080（可通过 OHPI_LISTEN 修改），不涉及 TLS。
# 重复执行同一命令会升级二进制并重启服务；token、配置与 session 数据全部保留。
#
# 子命令：install（默认）、status、uninstall
#
# 环境变量：
#   OHPI_VERSION      固定版本（默认最新 Release），例如 2.3.0
#   OHPI_REPO         仓库（默认 yearsyan/oh-pi-app）
#   OHPI_LISTEN       监听地址（默认 127.0.0.1:18080）
#   OHPI_DATA_DIR     session 数据目录（默认 ~/.local/state/oh-pi-app）
#   OHPI_WORK_DIR     默认工作空间目录（默认 $HOME）
#   OHPI_TOKEN        指定 token（默认自动生成；已有 token 文件时保留）
#   OHPI_PI_COMMAND   pi 可执行文件绝对路径（默认从 PATH 探测）
#   OHPI_NO_PI_INSTALL 非空时跳过 pi 自动安装（缺 pi 直接报错）
#   OHPI_HEALTH_URL   健康检查地址（默认由 OHPI_LISTEN 推导）
#   OHPI_NO_SERVICE   非空时只安装二进制与配置，不注册/启动服务

set -eu

label=io.github.yearsyan.ohpi.gateway
systemd_unit=ohpi-gateway.service

home=${HOME:?HOME is not set}
binary_dir=$home/.local/bin
binary_path=$binary_dir/ohpi-gateway
libexec_dir=$home/.local/libexec/oh-pi-app
launcher_path=$libexec_dir/ohpi-gateway-launch
config_dir=$home/.config/oh-pi-app
config_file=$config_dir/config.json
token_file=$config_dir/token
state_dir=$home/.local/state/oh-pi-app
log_dir=$state_dir/log
stdout_log=$log_dir/ohpi-gateway.stdout.log
stderr_log=$log_dir/ohpi-gateway.stderr.log
pid_file=$state_dir/gateway.pid
pi_env_path=
launch_agents_dir=$home/Library/LaunchAgents
plist_path=$launch_agents_dir/$label.plist
units_dir=$home/.config/systemd/user
unit_path=$units_dir/$systemd_unit

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

json_escape() {
	sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

detect_platform() {
	case $(uname -s) in
		Darwin) os=darwin ;;
		Linux) os=linux ;;
		*) die "unsupported operating system: $(uname -s) (only macOS and Linux are supported)" ;;
	esac
	case $(uname -m) in
		x86_64 | amd64) arch=amd64 ;;
		arm64 | aarch64) arch=arm64 ;;
		*) die "unsupported CPU architecture: $(uname -m) (only amd64 and arm64 are supported)" ;;
	esac
}

resolve_version() {
	repo=${OHPI_REPO:-yearsyan/oh-pi-app}
	version=${OHPI_VERSION-}
	if [ -n "$version" ]; then
		return
	fi
	# 跟随 releases/latest 的重定向得到 v<版本> 的最终 URL，避免依赖 GitHub API 限额。
	location=$(curl -fsSL -o /dev/null -w '%{url_effective}' \
		"https://github.com/$repo/releases/latest") || \
		die "could not resolve the latest release for $repo; set OHPI_VERSION explicitly"
	version=${location##*/}
	version=${version#v}
	case $version in
		'' | *[!0-9A-Za-z._+-]*) die "could not parse release version from: $location" ;;
	esac
}

compute_sha256() {
	file=$1
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$file" | sed 's/[[:space:]].*//'
	elif command -v shasum >/dev/null 2>&1; then
		shasum -a 256 "$file" | sed 's/[[:space:]].*//'
	elif command -v openssl >/dev/null 2>&1; then
		openssl dgst -sha256 "$file" | sed 's/^.*= //'
	else
		die "no SHA-256 tool found; install sha256sum, shasum or openssl"
	fi
}

generate_token() {
	if command -v openssl >/dev/null 2>&1; then
		openssl rand -hex 32
	elif command -v od >/dev/null 2>&1; then
		od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
	else
		die "cannot generate a token; install openssl or set OHPI_TOKEN"
	fi
}

# 监听地址只影响首次写入的 config.json（已有配置保留）；健康检查地址由其推导。
resolve_listen() {
	if [ -n "${OHPI_LISTEN-}" ]; then
		listen=$OHPI_LISTEN
	else
		listen=127.0.0.1:18080
	fi
	port=${listen##*:}
	case $port in
		'' | *[!0-9]*) die "cannot derive health-check port from OHPI_LISTEN=$listen" ;;
	esac
	health_url=${OHPI_HEALTH_URL-http://127.0.0.1:$port/healthz}
}

resolve_pi_command() {
	pi_command=${OHPI_PI_COMMAND-}
	if [ -z "$pi_command" ]; then
		pi_command=$(command -v pi 2>/dev/null || true)
	fi
	if [ -z "$pi_command" ]; then
		return
	fi
	[ -x "$pi_command" ] || die "pi is not executable: $pi_command"
	# fnm 通过 shell 特定路径暴露 pi；优先其稳定的 Node 安装目录，保证服务重启后仍可用。
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
}

# 检测不到 pi 时自动安装；逻辑与 App SSH 自动安装模式（unixPiInstallScript）保持一致。
install_pi() {
	require_command curl
	require_command tar
	require_command awk

	node_bin=
	npm_command=
	# 系统已有兼容 Node（>= 22.19）时直接复用，否则下载托管 Node。
	if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 &&
		node -e 'const [major, minor, patch] = process.versions.node.split(".").map(Number); process.exit(major > 22 || (major === 22 && (minor > 19 || (minor === 19 && patch >= 0))) ? 0 : 1)' >/dev/null 2>&1; then
		node_bin=$(dirname -- "$(command -v node)")
		npm_command=$(command -v npm)
	else
		case $os in
			darwin) node_platform=darwin ;;
			linux) node_platform=linux ;;
		esac
		case $arch in
			amd64) node_arch=x64 ;;
			arm64) node_arch=arm64 ;;
		esac
		node_dist=https://nodejs.org/dist/latest-v22.x
		node_root=$home/.local/share/oh-pi-app/node
		tmp_node=$tmp/pi-node
		mkdir -p "$tmp_node"
		curl -fsSL "$node_dist/SHASUMS256.txt" -o "$tmp_node/SHASUMS256.txt"
		node_file=$(awk -v suffix="-$node_platform-$node_arch.tar.gz" '
			index($2, "node-v22.") == 1 && substr($2, length($2) - length(suffix) + 1) == suffix { print $2; exit }
		' "$tmp_node/SHASUMS256.txt")
		case $node_file in
			node-v22.*-$node_platform-$node_arch.tar.gz) ;;
			*) die "could not resolve a compatible Node.js archive from $node_dist/SHASUMS256.txt" ;;
		esac
		expected=$(awk -v file="$node_file" '$2 == file { print $1; exit }' "$tmp_node/SHASUMS256.txt")
		curl -fsSL "$node_dist/$node_file" -o "$tmp_node/$node_file"
		actual=$(compute_sha256 "$tmp_node/$node_file")
		[ -n "$expected" ] && [ "$actual" = "$expected" ] || die "Node.js checksum verification failed"
		tar -xzf "$tmp_node/$node_file" -C "$tmp_node"
		node_dir=${node_file%.tar.gz}
		case $node_dir in
			node-v22.*-$node_platform-$node_arch) ;;
			*) die "unexpected Node.js archive layout: $node_file" ;;
		esac
		mkdir -p "$node_root"
		target=$node_root/$node_dir
		rm -rf "$target"
		mv "$tmp_node/$node_dir" "$target"
		rm -f "$node_root/current"
		ln -s "$target" "$node_root/current"
		node_bin=$node_root/current/bin
		npm_command=$node_bin/npm
		# 托管 Node 不在系统 PATH 中；pi 的 npm wrapper 运行时需要找到 node。
		pi_env_path=$node_bin:$home/.local/bin
	fi

	PATH="$node_bin:$home/.local/bin:$PATH"
	export PATH
	mkdir -p "$home/.local"
	note "Installing pi via npm (this can take a minute)"
	"$npm_command" install -g --ignore-scripts --prefix "$home/.local" --no-fund --no-audit '@earendil-works/pi-coding-agent'
}

ensure_pi() {
	[ -n "$pi_command" ] && return
	# 上次自动安装的托管 pi 已存在（不在 PATH 中）时直接复用。
	if [ -x "$home/.local/bin/pi" ] && "$home/.local/bin/pi" --version >/dev/null 2>&1; then
		pi_command=$home/.local/bin/pi
		note "Using pi: $pi_command"
		return
	fi
	if [ -n "${OHPI_NO_PI_INSTALL-}" ]; then
		die "pi was not found in PATH; install it (npm i -g @earendil-works/pi-coding-agent) or set OHPI_PI_COMMAND to its absolute path"
	fi
	note "pi was not found; installing Node.js 22 and pi (no sudo, no shell profile changes)"
	install_pi
	pi_command=$home/.local/bin/pi
	[ -x "$pi_command" ] || die "pi installation failed; check the output above"
	"$pi_command" --version >/dev/null 2>&1 || die "installed pi could not run: $pi_command"
	note "Installed pi: $pi_command"
}

download_and_verify() {
	tmp=$1
	artifact=ohpi-gateway-$version-$os-$arch
	base_url="https://github.com/$repo/releases/download/v$version"
	tmp_binary=$tmp/$artifact
	tmp_binary_final=$tmp/ohpi-gateway

	note "Downloading $artifact"
	curl -fsSL -o "$tmp_binary" "$base_url/$artifact"
	curl -fsSL -o "$tmp/SHA256SUMS.txt" "$base_url/SHA256SUMS.txt"
	chmod 755 "$tmp_binary"

	expected=$(sed -n "s/^\([0-9a-f]\{64\}\)  $artifact$/\1/p" "$tmp/SHA256SUMS.txt")
	[ -n "$expected" ] || die "SHA256SUMS.txt does not list $artifact"
	actual=$(compute_sha256 "$tmp_binary")
	[ "$actual" = "$expected" ] || die "SHA-256 mismatch for $artifact (expected $expected, got $actual)"

	version_output=$("$tmp_binary" --version) || die "downloaded gateway could not report its version"
	case $version_output in
		"ohpi-gateway $version") ;;
		*) die "downloaded gateway reported unexpected version: $version_output" ;;
	esac
	mv -f "$tmp_binary" "$tmp_binary_final"
}

install_token() {
	if [ -n "${OHPI_TOKEN-}" ]; then
		nl='
'
		case $OHPI_TOKEN in
			*"$nl"*) die "OHPI_TOKEN must be a single line" ;;
		esac
		umask 077
		printf '%s\n' "$OHPI_TOKEN" >"$token_file"
		chmod 600 "$token_file"
		note "Wrote provided token to $token_file"
	elif [ -s "$token_file" ]; then
		note "Keeping existing token: $token_file"
	else
		umask 077
		printf '%s\n' "$(generate_token)" >"$token_file"
		chmod 600 "$token_file"
		note "Generated token in $token_file"
	fi
}

install_config() {
	if [ -f "$config_file" ]; then
		note "Keeping existing config: $config_file"
		return
	fi
	{
		printf '{\n'
		printf '  "OHPI_LISTEN": "%s",\n' "$(printf '%s' "$listen" | json_escape)"
		printf '  "OHPI_DATA_DIR": "%s",\n' "$(printf '%s' "$data_dir" | json_escape)"
		printf '  "OHPI_WORK_DIR": "%s",\n' "$(printf '%s' "$work_dir" | json_escape)"
		printf '  "OHPI_PI_COMMAND": "%s"' "$(printf '%s' "$pi_command" | json_escape)"
		if [ -n "$pi_env_path" ]; then
			printf ',\n  "OHPI_PI_ENV_PATH": "%s"\n' "$(printf '%s' "$pi_env_path" | json_escape)"
		else
			printf '\n'
		fi
		printf '}\n'
	} >"$tmp/config.json"
	umask 077
	mv -f "$tmp/config.json" "$config_file"
	note "Wrote config: $config_file"
}

install_launcher() {
	# 从 token 文件读取 token 并 exec 网关；macOS LaunchAgent 与 Linux nohup 回退共用。
	umask 077
	cat >"$launcher_path" <<'EOF'
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
EOF
	chmod 755 "$launcher_path"
}

render_plist() {
	if [ -n "$pi_command" ]; then
		pi_dir=$(dirname -- "$pi_command")
	else
		pi_dir=
	fi
	launch_path=$binary_dir${pi_dir:+:$pi_dir}:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
	umask 077
	cat >"$tmp/$label.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>$label</string>
	<key>ProgramArguments</key>
	<array>
		<string>$launcher_path</string>
	</array>
	<key>EnvironmentVariables</key>
	<dict>
		<key>PATH</key>
		<string>$launch_path</string>
		<key>OHPI_BINARY</key>
		<string>$binary_path</string>
		<key>OHPI_CONFIG_FILE</key>
		<string>$config_file</string>
		<key>OHPI_PI_COMMAND</key>
		<string>$pi_command</string>
		<key>OHPI_TOKEN_FILE</key>
		<string>$token_file</string>
	</dict>
	<key>WorkingDirectory</key>
	<string>$work_dir</string>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>
	<key>ProcessType</key>
	<string>Background</string>
	<key>ThrottleInterval</key>
	<integer>10</integer>
	<key>Umask</key>
	<integer>63</integer>
	<key>StandardOutPath</key>
	<string>$stdout_log</string>
	<key>StandardErrorPath</key>
	<string>$stderr_log</string>
</dict>
</plist>
EOF
	chmod 644 "$tmp/$label.plist"
	if command -v plutil >/dev/null 2>&1; then
		plutil -lint "$tmp/$label.plist" >/dev/null || die "rendered LaunchAgent plist is invalid"
	fi
}

render_systemd_unit() {
	umask 077
	cat >"$tmp/$systemd_unit" <<'EOF'
[Unit]
Description=Oh Pi gateway
After=network-online.target

[Service]
Type=simple
ExecStart=%h/.local/bin/ohpi-gateway --config %h/.config/oh-pi-app/config.json --token-file %h/.config/oh-pi-app/token
Restart=on-failure
RestartSec=3
KillSignal=SIGTERM

[Install]
WantedBy=default.target
EOF
	chmod 644 "$tmp/$systemd_unit"
}

start_macos_service() {
	require_command launchctl
	uid=$(id -u)
	domain=gui/$uid
	launchctl print "$domain" >/dev/null 2>&1 || domain=user/$uid

	if launchctl print "$domain/$label" >/dev/null 2>&1; then
		note "Stopping existing LaunchAgent"
		launchctl bootout "$domain/$label"
	fi

	mkdir -p "$launch_agents_dir"
	install -m 0644 "$tmp/$label.plist" "$plist_path"

	launchctl enable "$domain/$label"
	attempt=1
	while ! launchctl bootstrap "$domain" "$plist_path"; do
		if [ "$attempt" -ge 5 ]; then
			die "failed to bootstrap $label after $attempt attempts; see $stderr_log"
		fi
		attempt=$((attempt + 1))
		note "LaunchAgent bootstrap not ready; retrying ($attempt/5)"
		sleep 1
	done
	launchctl kickstart -k "$domain/$label"
	service_kind="launchd ($label)"
}

stop_nohup() {
	if [ ! -f "$pid_file" ]; then
		return
	fi
	old_pid=$(cat "$pid_file")
	if kill -0 "$old_pid" 2>/dev/null; then
		kill "$old_pid" 2>/dev/null || true
		attempt=1
		while kill -0 "$old_pid" 2>/dev/null; do
			[ "$attempt" -ge 15 ] && break
			attempt=$((attempt + 1))
			sleep 1
		done
	fi
	rm -f "$pid_file"
}

start_linux_service() {
	if systemctl --user show-environment >/dev/null 2>&1; then
		mkdir -p "$units_dir"
		install -m 0644 "$tmp/$systemd_unit" "$unit_path"
		systemctl --user daemon-reload >/dev/null
		systemctl --user enable "$systemd_unit" >/dev/null 2>&1 || true
		systemctl --user restart "$systemd_unit" >/dev/null 2>&1 || \
			systemctl --user start "$systemd_unit" >/dev/null 2>&1 || \
			die "systemctl --user could not start $systemd_unit; see $stderr_log"
		service_kind="systemd user ($systemd_unit)"
	else
		# 无 systemd user manager（部分 WSL / 容器）时回退 nohup 后台进程。
		stop_nohup
		note "systemd user manager is not available; falling back to a nohup background process"
		umask 077
		OHPI_BINARY=$binary_path OHPI_TOKEN_FILE=$token_file OHPI_CONFIG_FILE=$config_file \
			OHPI_PI_COMMAND=$pi_command \
			nohup "$launcher_path" >>"$stdout_log" 2>>"$stderr_log" &
		echo $! >"$pid_file"
		service_kind="nohup (pid $pid_file)"
	fi
}

stop_linux_service() {
	systemctl --user disable --now "$systemd_unit" >/dev/null 2>&1 || true
	rm -f "$unit_path"
	systemctl --user daemon-reload >/dev/null 2>&1 || true
	stop_nohup
}

wait_healthy() {
	note "Waiting for $health_url"
	attempt=1
	while ! curl -fsS "$health_url" >/dev/null 2>&1; do
		if [ "$attempt" -ge 20 ]; then
			printf 'error: gateway did not become healthy within %ss; check %s\n' \
				"$((attempt - 1))" "$stderr_log" >&2
			exit 1
		fi
		attempt=$((attempt + 1))
		sleep 1
	done
	note "Gateway is healthy: $health_url"
}

cmd_install() {
	require_command curl
	require_command uname
	require_command mktemp
	require_command sed
	require_command mv

	detect_platform
	resolve_listen
	resolve_version
	resolve_pi_command

	work_dir=${OHPI_WORK_DIR:-$home}
	[ -d "$work_dir" ] || die "OHPI_WORK_DIR is not a directory: $work_dir"
	data_dir=${OHPI_DATA_DIR:-$state_dir}

	tmp=$(mktemp -d "${TMPDIR:-/tmp}/oh-pi-app-install.XXXXXX")
	cleanup() {
		rm -rf "$tmp"
	}
	trap cleanup EXIT HUP INT TERM

	ensure_pi

	download_and_verify "$tmp"

	note "Preparing installation directories"
	mkdir -p "$binary_dir" "$libexec_dir" "$config_dir" "$data_dir" "$log_dir"

	install_token
	install_config
	install_launcher
	render_plist
	render_systemd_unit

	note "Installing gateway binary"
	install -m 0755 "$tmp/ohpi-gateway" "$binary_path"
	version_output=$("$binary_path" --version)
	note "Installed $version_output"

	if [ -n "${OHPI_NO_SERVICE-}" ]; then
		note "OHPI_NO_SERVICE is set; skipping service registration and startup"
		note "Start manually: OHPI_TOKEN_FILE=$token_file $binary_path"
		return
	fi

	case $os in
		darwin) start_macos_service ;;
		linux) start_linux_service ;;
	esac

	wait_healthy

	note "Done"
	printf '\n'
	printf '  Gateway : %s (%s, %s)\n' "http://127.0.0.1:$port" "$version_output" "$service_kind"
	printf '  Token   : %s\n' "$token_file"
	printf '            %s\n' "$(sed -n '1p' "$token_file")"
	printf '  Work dir: %s\n' "$work_dir"
	printf '  Data dir: %s\n' "$data_dir"
	printf '\n'
	printf '  Re-run this command to upgrade; token, config and sessions are preserved.\n'
	printf '  Uninstall: curl -fsSL https://raw.githubusercontent.com/%s/main/scripts/install.sh | sh -s uninstall\n' "$repo"
}

cmd_status() {
	detect_platform
	resolve_listen
	if [ -x "$binary_path" ]; then
		binary_version=$("$binary_path" --version 2>/dev/null || printf 'unknown version')
	else
		binary_version='not installed'
	fi
	service_kind=none
	case $os in
		darwin)
			uid=$(id -u)
			domain=gui/$uid
			launchctl print "$domain" >/dev/null 2>&1 || domain=user/$uid
			if launchctl print "$domain/$label" >/dev/null 2>&1; then
				service_kind="running (launchd $label)"
			else
				service_kind="stopped (launchd $label)"
			fi
			;;
		linux)
			if systemctl --user show-environment >/dev/null 2>&1; then
				if systemctl --user is-active --quiet "$systemd_unit" 2>/dev/null; then
					service_kind="running (systemd user $systemd_unit)"
				else
					service_kind="stopped (systemd user $systemd_unit)"
				fi
			elif [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
				service_kind="running (nohup pid $(cat "$pid_file"))"
			else
				service_kind="stopped (nohup)"
			fi
			;;
	esac
	if curl -fsS "$health_url" >/dev/null 2>&1; then
		health="OK ($health_url)"
	else
		health="unreachable ($health_url)"
	fi
	printf '==> ohpi-gateway status\n'
	printf '  binary : %s (%s)\n' "$binary_path" "$binary_version"
	printf '  service: %s\n' "$service_kind"
	printf '  health : %s\n' "$health"
	printf '  token  : %s\n' "$token_file"
}

cmd_uninstall() {
	detect_platform
	case $os in
		darwin)
			if command -v launchctl >/dev/null 2>&1; then
				uid=$(id -u)
				domain=gui/$uid
				launchctl print "$domain" >/dev/null 2>&1 || domain=user/$uid
				launchctl bootout "$domain/$label" >/dev/null 2>&1 || true
			fi
			rm -f "$plist_path"
			;;
		linux)
			if command -v systemctl >/dev/null 2>&1; then
				stop_linux_service
			else
				stop_nohup
			fi
			;;
	esac
	printf '==> ohpi-gateway uninstalled (service definition removed)\n'
	printf '  Kept in place (re-running the installer reuses them):\n'
	printf '    binary : %s\n' "$binary_path"
	printf '    config : %s\n' "$config_file"
	printf '    token  : %s\n' "$token_file"
	printf '    data   : %s\n' "$state_dir"
	printf '  Remove those paths manually to fully delete the installation.\n'
}

usage() {
	printf 'usage: %s [install|status|uninstall]\n' "${0##*/}" >&2
	exit 2
}

cmd=${1:-install}
case $cmd in
	install) cmd_install ;;
	status) cmd_status ;;
	uninstall) cmd_uninstall ;;
	*) usage ;;
esac
