package scheduledtask

import (
	"errors"
	"fmt"
	"path/filepath"
	"testing"
	"time"
)

func TestStorePersistsAndClaimsTask(t *testing.T) {
	now := time.Date(2026, time.August, 12, 0, 0, 0, 0, time.UTC)
	store, err := newStore(filepath.Join(t.TempDir(), "tasks"), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	anchor := now.Add(time.Hour)
	task, err := store.Create(Definition{
		Name:        "检查项目",
		WorkspaceID: "workspace",
		Prompt:      "运行测试并总结失败。",
		Enabled:     true,
		Schedule: Schedule{
			Kind:         ScheduleInterval,
			EverySeconds: 3600,
			AnchorAt:     &anchor,
		},
	})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if task.NextRunAt == nil || !task.NextRunAt.Equal(anchor) {
		t.Fatalf("next = %v, want %s", task.NextRunAt, anchor)
	}

	now = anchor
	claimed, run, err := store.claimDue(task.ID, anchor)
	if err != nil {
		t.Fatalf("claimDue: %v", err)
	}
	if claimed.CurrentRun == nil || claimed.CurrentRun.ID != run.ID {
		t.Fatalf("current run = %#v", claimed.CurrentRun)
	}
	if claimed.NextRunAt == nil || !claimed.NextRunAt.Equal(anchor.Add(time.Hour)) {
		t.Fatalf("next after claim = %v", claimed.NextRunAt)
	}
	if err := store.markSession(task.ID, run.ID, "session-1"); err != nil {
		t.Fatalf("markSession: %v", err)
	}
	now = now.Add(5 * time.Minute)
	if err := store.complete(task.ID, run.ID, RunSucceeded, ""); err != nil {
		t.Fatalf("complete: %v", err)
	}
	loaded, err := store.Get(task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.CurrentRun != nil || loaded.LastRun == nil || loaded.LastRun.SessionID != "session-1" || loaded.LastRun.Status != RunSucceeded {
		t.Fatalf("completed task = %#v", loaded)
	}
}

func TestStoreNormalizesAndPersistsTaskSkills(t *testing.T) {
	now := time.Date(2026, time.August, 12, 0, 0, 0, 0, time.UTC)
	store, err := newStore(filepath.Join(t.TempDir(), "tasks"), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	anchor := now.Add(time.Minute)
	paths := []string{" skills/task ", "/srv/shared-skills", "skills/task"}
	task, err := store.Create(Definition{
		Name:        "skill task",
		WorkspaceID: "workspace",
		SkillPaths:  paths,
		NoSkills:    true,
		Prompt:      "check",
		Enabled:     false,
		Schedule:    Schedule{Kind: ScheduleInterval, EverySeconds: 60, AnchorAt: &anchor},
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"skills/task", "/srv/shared-skills"}
	if fmt.Sprint(task.SkillPaths) != fmt.Sprint(want) || !task.NoSkills {
		t.Fatalf("created task skills = %q, no_skills=%v", task.SkillPaths, task.NoSkills)
	}
	paths[0] = "mutated"
	task.SkillPaths[0] = "mutated"
	loaded, err := store.Get(task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if fmt.Sprint(loaded.SkillPaths) != fmt.Sprint(want) || !loaded.NoSkills {
		t.Fatalf("persisted task skills = %q, no_skills=%v", loaded.SkillPaths, loaded.NoSkills)
	}
}

func TestDefinitionRejectsInvalidTaskSkillPath(t *testing.T) {
	err := (Definition{
		Name:        "skill task",
		WorkspaceID: "workspace",
		SkillPaths:  []string{"skills/valid", "\x00invalid"},
		Prompt:      "check",
		Schedule:    Schedule{Kind: ScheduleInterval, EverySeconds: 60},
	}).Validate()
	if err == nil {
		t.Fatal("definition accepted a NUL-containing skill path")
	}
}

func TestStoreRecoveryDoesNotRepeatClaimedOnceTask(t *testing.T) {
	now := time.Date(2026, time.August, 12, 0, 0, 0, 0, time.UTC)
	store, err := newStore(filepath.Join(t.TempDir(), "tasks"), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	at := now.Add(time.Minute)
	task, err := store.Create(Definition{
		Name:        "一次执行",
		WorkspaceID: "workspace",
		Prompt:      "检查状态",
		Enabled:     true,
		Schedule:    Schedule{Kind: ScheduleOnce, At: &at},
	})
	if err != nil {
		t.Fatal(err)
	}
	now = at
	_, run, err := store.claimDue(task.ID, at)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Recover(); err != nil {
		t.Fatal(err)
	}
	loaded, err := store.Get(task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Enabled || loaded.NextRunAt != nil || loaded.CurrentRun != nil {
		t.Fatalf("recovered once task remained armed: %#v", loaded)
	}
	if loaded.LastRun == nil || loaded.LastRun.ID != run.ID || loaded.LastRun.Status != RunInterrupted {
		t.Fatalf("last run = %#v", loaded.LastRun)
	}
}

func TestStoreSkipsOverlapAndAdvancesSchedule(t *testing.T) {
	now := time.Date(2026, time.August, 12, 0, 0, 0, 0, time.UTC)
	store, err := newStore(filepath.Join(t.TempDir(), "tasks"), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	anchor := now.Add(time.Minute)
	task, err := store.Create(Definition{
		Name: "频繁检查", WorkspaceID: "workspace", Prompt: "check", Enabled: true,
		Schedule: Schedule{Kind: ScheduleInterval, EverySeconds: 60, AnchorAt: &anchor},
	})
	if err != nil {
		t.Fatal(err)
	}
	now = anchor
	if _, _, err := store.claimDue(task.ID, anchor); err != nil {
		t.Fatal(err)
	}
	now = anchor.Add(time.Minute)
	_, _, err = store.claimDue(task.ID, now)
	if !errors.Is(err, ErrRunning) {
		t.Fatalf("overlap error = %v", err)
	}
	loaded, err := store.Get(task.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.NextRunAt == nil || !loaded.NextRunAt.Equal(now.Add(time.Minute)) {
		t.Fatalf("next after overlap = %v", loaded.NextRunAt)
	}
	if loaded.LastRun == nil || loaded.LastRun.Status != RunSkipped {
		t.Fatalf("last run = %#v", loaded.LastRun)
	}
}

func TestStoreRejectsPastOneShotWhenEnabled(t *testing.T) {
	now := time.Date(2026, time.August, 12, 0, 0, 0, 0, time.UTC)
	store, err := newStore(filepath.Join(t.TempDir(), "tasks"), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	past := now.Add(-time.Minute)
	_, err = store.Create(Definition{
		Name: "past", WorkspaceID: "workspace", Prompt: "check", Enabled: true,
		Schedule: Schedule{Kind: ScheduleOnce, At: &past},
	})
	if err == nil {
		t.Fatal("past one-shot task was accepted")
	}
}

func TestStoreRejectsDefinitionUpdateWhileRunning(t *testing.T) {
	now := time.Date(2026, time.August, 12, 0, 0, 0, 0, time.UTC)
	store, err := newStore(filepath.Join(t.TempDir(), "tasks"), func() time.Time { return now })
	if err != nil {
		t.Fatal(err)
	}
	anchor := now.Add(time.Minute)
	task, err := store.Create(Definition{
		Name: "running", WorkspaceID: "workspace", Prompt: "check", Enabled: true,
		Schedule: Schedule{Kind: ScheduleInterval, EverySeconds: 60, AnchorAt: &anchor},
	})
	if err != nil {
		t.Fatal(err)
	}
	now = anchor
	if _, _, err := store.claimDue(task.ID, anchor); err != nil {
		t.Fatal(err)
	}
	_, err = store.Update(task.ID, Definition{
		Name: "changed", WorkspaceID: "workspace", Prompt: "different", Enabled: true,
		Schedule: Schedule{Kind: ScheduleInterval, EverySeconds: 120, AnchorAt: &anchor},
	})
	if !errors.Is(err, ErrRunning) {
		t.Fatalf("Update error = %v", err)
	}
}
