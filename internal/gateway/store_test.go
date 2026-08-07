package gateway

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSessionStorePersistsMetadata(t *testing.T) {
	store, err := newSessionStore(t.TempDir())
	if err != nil {
		t.Fatalf("create store: %v", err)
	}
	workspaceID, err := newSessionID()
	if err != nil {
		t.Fatal(err)
	}
	created, dir, err := store.create(workspaceID)
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	if !validSessionID(created.ID) {
		t.Fatalf("invalid session id %q", created.ID)
	}
	if created.WorkspaceID != workspaceID {
		t.Fatalf("created session workspace = %q, want %q", created.WorkspaceID, workspaceID)
	}
	if created.NameSet {
		t.Fatal("new session unexpectedly has an authoritative name")
	}
	loaded, loadedDir, err := store.load(created.ID)
	if err != nil {
		t.Fatalf("load session: %v", err)
	}
	if loaded != created || loadedDir != dir {
		t.Fatalf("loaded session = (%#v, %q), want (%#v, %q)", loaded, loadedDir, created, dir)
	}
	info, err := os.Stat(filepath.Join(dir, metadataFileName))
	if err != nil {
		t.Fatalf("stat metadata: %v", err)
	}
	if got := info.Mode().Perm(); got != 0o600 {
		t.Fatalf("metadata permissions = %o, want 600", got)
	}
}

func TestSessionStoreAdoptsOnlyTheFirstObservedName(t *testing.T) {
	store, err := newSessionStore(t.TempDir())
	if err != nil {
		t.Fatalf("create store: %v", err)
	}
	workspaceID, err := newSessionID()
	if err != nil {
		t.Fatal(err)
	}
	created, _, err := store.create(workspaceID)
	if err != nil {
		t.Fatalf("create session: %v", err)
	}

	accepted, err := store.adoptName(created.ID, "generated title")
	if err != nil || !accepted {
		t.Fatalf("adopt first name = (%v, %v), want (true, nil)", accepted, err)
	}
	accepted, err = store.adoptName(created.ID, "late generated title")
	if err != nil || accepted {
		t.Fatalf("adopt conflicting name = (%v, %v), want (false, nil)", accepted, err)
	}
	accepted, err = store.adoptName(created.ID, "generated title")
	if err != nil || !accepted {
		t.Fatalf("accept matching name = (%v, %v), want (true, nil)", accepted, err)
	}

	if _, err := store.rename(created.ID, "manual title"); err != nil {
		t.Fatalf("rename session: %v", err)
	}
	accepted, err = store.adoptName(created.ID, "generated title")
	if err != nil || accepted {
		t.Fatalf("adopt after manual rename = (%v, %v), want (false, nil)", accepted, err)
	}
	loaded, _, err := store.load(created.ID)
	if err != nil {
		t.Fatalf("load session: %v", err)
	}
	if loaded.Name != "manual title" || !loaded.NameSet {
		t.Fatalf("loaded metadata = %#v, want authoritative manual title", loaded)
	}
}

func TestSessionStoreRejectsTraversalAndUnknownIDs(t *testing.T) {
	store, err := newSessionStore(t.TempDir())
	if err != nil {
		t.Fatalf("create store: %v", err)
	}
	for _, id := range []string{
		"../../etc/passwd",
		"11111111-1111-4111-8111-11111111111Z",
		"11111111-1111-4111-8111-111111111111",
	} {
		if _, _, err := store.load(id); !errors.Is(err, errSessionNotFound) {
			t.Errorf("load(%q) error = %v, want session not found", id, err)
		}
	}
}

func TestSessionStoreRequiresWorkspaceIdentity(t *testing.T) {
	dataDir := t.TempDir()
	store, err := newSessionStore(dataDir)
	if err != nil {
		t.Fatalf("create store: %v", err)
	}
	if _, _, err := store.create(""); err == nil {
		t.Fatal("create session without workspace id unexpectedly succeeded")
	}

	id, err := newSessionID()
	if err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(dataDir, "sessions", id)
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatalf("create legacy session directory: %v", err)
	}
	now := time.Now().UTC()
	if err := writeMetadata(dir, sessionMetadata{ID: id, CreatedAt: now, UpdatedAt: now}); err != nil {
		t.Fatalf("write metadata without workspace: %v", err)
	}
	if _, _, err := store.load(id); err == nil || !strings.Contains(err.Error(), "invalid session workspace") {
		t.Fatalf("load metadata without workspace error = %v", err)
	}
}

func TestSessionStoreDiscardOnlyRemovesAllocatedSession(t *testing.T) {
	store, err := newSessionStore(t.TempDir())
	if err != nil {
		t.Fatalf("create store: %v", err)
	}
	workspaceID, err := newSessionID()
	if err != nil {
		t.Fatal(err)
	}
	created, dir, err := store.create(workspaceID)
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	if err := store.discard(created.ID); err != nil {
		t.Fatalf("discard session: %v", err)
	}
	if _, err := os.Stat(dir); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("discarded directory stat error = %v, want not exist", err)
	}
	if err := store.discard("../../outside"); err == nil {
		t.Fatal("discard accepted path traversal")
	}
}
