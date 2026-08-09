package gateway

import "testing"

func TestSyncDirectory(t *testing.T) {
	if err := syncDirectory(t.TempDir()); err != nil {
		t.Fatalf("sync directory: %v", err)
	}
}
