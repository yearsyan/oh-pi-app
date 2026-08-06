//go:build windows

package gateway

import (
	"context"
	"encoding/base64"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
)

func newPiProcess(command string, args ...string) *exec.Cmd {
	return newWindowsPiProcess(nil, command, args...)
}

func newPiProcessContext(ctx context.Context, command string, args ...string) *exec.Cmd {
	return newWindowsPiProcess(ctx, command, args...)
}

func newWindowsPiProcess(ctx context.Context, command string, args ...string) *exec.Cmd {
	makeCommand := func(name string, values ...string) *exec.Cmd {
		if ctx == nil {
			return exec.Command(name, values...)
		}
		created := exec.CommandContext(ctx, name, values...)
		created.Cancel = func() error {
			killPiProcess(created)
			return nil
		}
		return created
	}
	switch strings.ToLower(filepath.Ext(command)) {
	case ".bat", ".cmd", ".ps1":
		return makeCommand(
			"powershell.exe",
			"-NoLogo",
			"-NoProfile",
			"-NonInteractive",
			"-ExecutionPolicy",
			"Bypass",
			"-EncodedCommand",
			encodePowerShellPiCommand(command, args),
		)
	default:
		return makeCommand(command, args...)
	}
}

func encodePowerShellPiCommand(command string, args []string) string {
	decode := func(value string) string {
		return "[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" +
			base64.StdEncoding.EncodeToString([]byte(value)) + "'))"
	}
	var script strings.Builder
	script.WriteString("$ErrorActionPreference='Stop';$utf8=[Text.UTF8Encoding]::new($false);")
	script.WriteString("[Console]::InputEncoding=$utf8;[Console]::OutputEncoding=$utf8;$OutputEncoding=$utf8;$c=")
	script.WriteString(decode(command))
	script.WriteString(";$a=@(")
	for index, arg := range args {
		if index > 0 {
			script.WriteByte(',')
		}
		script.WriteString(decode(arg))
	}
	script.WriteString(");& $c @a;if($null -eq $LASTEXITCODE){exit 0};exit $LASTEXITCODE")

	plain := []byte(script.String())
	utf16LE := make([]byte, len(plain)*2)
	for index, value := range plain {
		utf16LE[index*2] = value
	}
	return base64.StdEncoding.EncodeToString(utf16LE)
}

func killPiProcess(command *exec.Cmd) {
	killer := exec.Command(
		"taskkill.exe",
		"/PID",
		strconv.Itoa(command.Process.Pid),
		"/T",
		"/F",
	)
	if err := killer.Run(); err != nil {
		_ = command.Process.Kill()
	}
}
