package scheduledtask

import (
	"context"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"
)

func TestEngineAutomaticallyExecutesDueOneShot(t *testing.T) {
	store, err := newStore(filepath.Join(t.TempDir(), "tasks"), time.Now)
	if err != nil {
		t.Fatal(err)
	}
	at := time.Now().UTC().Add(150 * time.Millisecond)
	task, err := store.Create(Definition{
		Name:        "one shot",
		WorkspaceID: "workspace",
		Prompt:      "check",
		Enabled:     true,
		Schedule:    Schedule{Kind: ScheduleOnce, At: &at},
	})
	if err != nil {
		t.Fatal(err)
	}
	runs := make(chan Run, 1)
	engine := NewEngine(
		store,
		RunnerFunc(func(_ context.Context, _ Task, run Run, _ Reporter) error {
			runs <- run
			return nil
		}),
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	)
	if err := engine.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		if err := engine.Stop(ctx); err != nil {
			t.Errorf("Stop: %v", err)
		}
	})

	select {
	case run := <-runs:
		if !run.ScheduledFor.Equal(at) || run.Manual {
			t.Fatalf("run = %#v", run)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("scheduled task did not run")
	}

	deadline := time.Now().Add(time.Second)
	for {
		loaded, getErr := store.Get(task.ID)
		if getErr != nil {
			t.Fatal(getErr)
		}
		if loaded.CurrentRun == nil && loaded.LastRun != nil {
			if loaded.Enabled || loaded.NextRunAt != nil || loaded.LastRun.Status != RunSucceeded {
				t.Fatalf("completed task = %#v", loaded)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("task did not complete: %#v", loaded)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestEngineExecutesHTTPTriggeredTaskWithEventData(t *testing.T) {
	store, err := newStore(filepath.Join(t.TempDir(), "tasks"), time.Now)
	if err != nil {
		t.Fatal(err)
	}
	task, err := store.Create(Definition{
		Name: "webhook", WorkspaceID: "workspace", Prompt: "check", Enabled: true,
		Schedule: Schedule{Kind: ScheduleHTTP},
	})
	if err != nil {
		t.Fatal(err)
	}
	runs := make(chan Run, 1)
	engine := NewEngine(
		store,
		RunnerFunc(func(_ context.Context, _ Task, run Run, _ Reporter) error {
			runs <- run
			return nil
		}),
		slog.New(slog.NewTextHandler(io.Discard, nil)),
	)
	if err := engine.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		if err := engine.Stop(ctx); err != nil {
			t.Errorf("Stop: %v", err)
		}
	})

	triggeredAt := time.Now()
	_, claimed, err := engine.TriggerEvent(task.EventKey, `{"ref":"main"}`, 200*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if claimed.ScheduledFor.Before(triggeredAt.Add(190 * time.Millisecond)) {
		t.Fatalf("scheduled_for = %s, want at least 190ms after %s", claimed.ScheduledFor, triggeredAt)
	}
	select {
	case run := <-runs:
		t.Fatalf("HTTP-triggered task ran before its delay: %#v", run)
	case <-time.After(100 * time.Millisecond):
	}
	select {
	case run := <-runs:
		if run.ID != claimed.ID || run.EventData != `{"ref":"main"}` || run.Manual {
			t.Fatalf("run = %#v", run)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("HTTP-triggered task did not run")
	}
}
