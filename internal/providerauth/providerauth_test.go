package providerauth

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestInstallProviderAuthExtension(t *testing.T) {
	dataDir := t.TempDir()
	path, err := Install(dataDir)
	if err != nil {
		t.Fatalf("install provider extension: %v", err)
	}
	if want := filepath.Join(dataDir, "runtime", extensionFileName); path != want {
		t.Fatalf("provider extension path = %q, want %q", path, want)
	}
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read provider extension: %v", err)
	}
	if !bytes.Equal(contents, extensionSource) {
		t.Fatal("installed provider extension differs from embedded source")
	}
	if info, err := os.Stat(path); err != nil {
		t.Fatalf("stat provider extension: %v", err)
	} else if got := info.Mode().Perm(); got != 0o600 {
		t.Fatalf("provider extension permissions = %o, want 600", got)
	}
	if !bytes.Contains(extensionSource, []byte(`builtinProviders()`)) {
		t.Fatal("provider extension does not restrict management to built-in providers")
	}
	if !bytes.Contains(extensionSource, []byte(`runtime.login`)) ||
		!bytes.Contains(extensionSource, []byte(`runtime.logout`)) {
		t.Fatal("provider extension does not delegate credential mutations to pi")
	}
}
