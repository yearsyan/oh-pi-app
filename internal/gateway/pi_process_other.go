//go:build !windows

package gateway

import (
	"context"
	"os/exec"
)

func newPiProcess(command string, args ...string) *exec.Cmd {
	return exec.Command(command, args...)
}

func newPiProcessContext(ctx context.Context, command string, args ...string) *exec.Cmd {
	return exec.CommandContext(ctx, command, args...)
}

func killPiProcess(command *exec.Cmd) {
	_ = command.Process.Kill()
}
