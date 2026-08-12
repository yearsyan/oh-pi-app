package gateway

import (
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestPruneScheduledSessionsDeletesOnlyExpiredInactiveTaskSessions(t *testing.T) {
	dataDir := t.TempDir()
	store, err := newSessionStore(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	workspaces, err := newWorkspaceStore(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	workspace, _, err := workspaces.ensure(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	manager := newSessionManager(
		Config{Logger: slog.New(slog.NewTextHandler(io.Discard, nil))},
		store,
		workspaces,
	)

	expired, _, err := store.createWithSource(workspace.ID, sessionSourceScheduledTask)
	if err != nil {
		t.Fatal(err)
	}
	recent, _, err := store.createWithSource(workspace.ID, sessionSourceScheduledTask)
	if err != nil {
		t.Fatal(err)
	}
	regular, _, err := store.create(workspace.ID)
	if err != nil {
		t.Fatal(err)
	}
	cutoff := time.Now().UTC().Add(-7 * 24 * time.Hour)
	setSessionUpdatedAt(t, store, expired.ID, cutoff.Add(-time.Minute))
	setSessionUpdatedAt(t, store, regular.ID, cutoff.Add(-time.Minute))

	deleted, err := manager.pruneScheduledSessions(context.Background(), cutoff)
	if err != nil {
		t.Fatal(err)
	}
	if deleted != 1 {
		t.Fatalf("deleted = %d, want 1", deleted)
	}
	if _, err := os.Stat(filepath.Join(dataDir, "sessions", expired.ID)); !os.IsNotExist(err) {
		t.Fatalf("expired scheduled session stat = %v, want not exist", err)
	}
	for _, id := range []string{recent.ID, regular.ID} {
		if _, _, err := store.load(id); err != nil {
			t.Fatalf("retained session %s: %v", id, err)
		}
	}
}

func setSessionUpdatedAt(t *testing.T, store *sessionStore, id string, updatedAt time.Time) {
	t.Helper()
	meta, dir, err := store.load(id)
	if err != nil {
		t.Fatal(err)
	}
	meta.UpdatedAt = updatedAt
	if err := replaceMetadata(dir, meta); err != nil {
		t.Fatal(err)
	}
}
