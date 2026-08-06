// Package providerauth installs the pi extension used for built-in provider authentication.
package providerauth

import (
	"bytes"
	_ "embed"
	"fmt"
	"os"
	"path/filepath"
)

const extensionFileName = "ohpi-provider-auth.ts"

//go:embed provider-auth.ts
var extensionSource []byte

// Install materializes the embedded extension below dataDir and returns the
// absolute path that should be passed to pi with --extension.
func Install(dataDir string) (string, error) {
	root, err := filepath.Abs(dataDir)
	if err != nil {
		return "", fmt.Errorf("resolve provider extension directory: %w", err)
	}
	runtimeDir := filepath.Join(root, "runtime")
	if err := os.MkdirAll(runtimeDir, 0o700); err != nil {
		return "", fmt.Errorf("create provider extension directory: %w", err)
	}
	info, err := os.Lstat(runtimeDir)
	if err != nil {
		return "", fmt.Errorf("inspect provider extension directory: %w", err)
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return "", fmt.Errorf("provider extension path %q is not a directory", runtimeDir)
	}
	if err := os.Chmod(runtimeDir, 0o700); err != nil {
		return "", fmt.Errorf("secure provider extension directory: %w", err)
	}

	target := filepath.Join(runtimeDir, extensionFileName)
	if current, readErr := os.ReadFile(target); readErr == nil && bytes.Equal(current, extensionSource) {
		if err := os.Chmod(target, 0o600); err != nil {
			return "", fmt.Errorf("secure provider extension: %w", err)
		}
		return target, nil
	}

	temporary, err := os.CreateTemp(runtimeDir, ".ohpi-provider-auth-*.tmp")
	if err != nil {
		return "", fmt.Errorf("create temporary provider extension: %w", err)
	}
	temporaryPath := temporary.Name()
	committed := false
	defer func() {
		_ = temporary.Close()
		if !committed {
			_ = os.Remove(temporaryPath)
		}
	}()
	if err := temporary.Chmod(0o600); err != nil {
		return "", fmt.Errorf("secure temporary provider extension: %w", err)
	}
	if _, err := temporary.Write(extensionSource); err != nil {
		return "", fmt.Errorf("write temporary provider extension: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		return "", fmt.Errorf("sync temporary provider extension: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return "", fmt.Errorf("close temporary provider extension: %w", err)
	}
	if err := os.Rename(temporaryPath, target); err != nil {
		return "", fmt.Errorf("install provider extension: %w", err)
	}
	committed = true
	return target, nil
}
