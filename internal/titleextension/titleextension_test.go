package titleextension

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestInstallMaterializesEmbeddedExtension(t *testing.T) {
	dataDir := t.TempDir()
	path, err := Install(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(dataDir, "runtime", extensionFileName); path != want {
		t.Fatalf("Install path = %q, want %q", path, want)
	}
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(contents, extensionSource) {
		t.Fatal("installed extension differs from embedded source")
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := info.Mode().Perm(); got != 0o600 {
		t.Fatalf("extension permissions = %o, want 600", got)
	}

	if _, err := Install(dataDir); err != nil {
		t.Fatalf("second Install failed: %v", err)
	}
}

func TestEmbeddedExtensionStartsTitleBeforeAgent(t *testing.T) {
	if !bytes.Contains(extensionSource, []byte(`pi.on("before_agent_start"`)) {
		t.Fatal("embedded extension does not start title generation before the agent")
	}
	if bytes.Contains(extensionSource, []byte(`pi.on("agent_settled"`)) {
		t.Fatal("embedded extension still waits for agent_settled")
	}
}

func TestEmbeddedExtensionPreservesRequestLanguage(t *testing.T) {
	for _, rule := range []string{
		"same language as the user's actual request",
		"Never translate it or switch languages",
		"For a Chinese request, use 4-12 Chinese characters",
		"For an English request, use 3-8 English words",
	} {
		if !bytes.Contains(extensionSource, []byte(rule)) {
			t.Fatalf("embedded extension title prompt is missing language rule %q", rule)
		}
	}
}
