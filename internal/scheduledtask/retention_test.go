package scheduledtask

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"
)

func TestRetentionCleanerRunsImmediatelyWithRetentionCutoff(t *testing.T) {
	now := time.Date(2026, time.August, 12, 8, 0, 0, 0, time.UTC)
	cutoffs := make(chan time.Time, 1)
	cleaner := newRetentionCleaner(
		7*24*time.Hour,
		time.Hour,
		func(_ context.Context, cutoff time.Time) (int, error) {
			cutoffs <- cutoff
			return 0, nil
		},
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		func() time.Time { return now },
	)
	if err := cleaner.Start(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	select {
	case cutoff := <-cutoffs:
		if want := now.Add(-7 * 24 * time.Hour); !cutoff.Equal(want) {
			t.Fatalf("cutoff = %s, want %s", cutoff, want)
		}
	case <-ctx.Done():
		t.Fatal("retention cleaner did not run")
	}
	if err := cleaner.Stop(ctx); err != nil {
		t.Fatal(err)
	}
}
