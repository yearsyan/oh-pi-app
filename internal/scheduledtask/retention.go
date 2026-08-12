package scheduledtask

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"
)

const scheduledSessionCleanupInterval = time.Hour

// SessionCleanupFunc removes scheduled-task sessions last active before cutoff.
type SessionCleanupFunc func(context.Context, time.Time) (int, error)

// RetentionCleaner periodically applies retention to sessions created by tasks.
type RetentionCleaner struct {
	retention time.Duration
	interval  time.Duration
	cleanup   SessionCleanupFunc
	logger    *slog.Logger
	now       func() time.Time

	mu      sync.Mutex
	started bool
	cancel  context.CancelFunc
	wg      sync.WaitGroup
}

// NewRetentionCleaner constructs an hourly cleaner using the supplied retention.
func NewRetentionCleaner(
	retention time.Duration,
	cleanup SessionCleanupFunc,
	logger *slog.Logger,
) *RetentionCleaner {
	return newRetentionCleaner(retention, scheduledSessionCleanupInterval, cleanup, logger, time.Now)
}

func newRetentionCleaner(
	retention time.Duration,
	interval time.Duration,
	cleanup SessionCleanupFunc,
	logger *slog.Logger,
	now func() time.Time,
) *RetentionCleaner {
	if logger == nil {
		logger = slog.Default()
	}
	return &RetentionCleaner{
		retention: retention,
		interval:  interval,
		cleanup:   cleanup,
		logger:    logger,
		now:       now,
	}
}

// Start begins cleanup and performs the first pass immediately in the background.
func (cleaner *RetentionCleaner) Start() error {
	cleaner.mu.Lock()
	defer cleaner.mu.Unlock()
	if cleaner.started {
		return nil
	}
	if cleaner.retention <= 0 || cleaner.interval <= 0 || cleaner.cleanup == nil || cleaner.now == nil {
		return errors.New("scheduled session retention cleaner is not configured")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cleaner.cancel = cancel
	cleaner.started = true
	cleaner.wg.Add(1)
	go cleaner.loop(ctx)
	return nil
}

// Stop cancels cleanup and waits for an active pass to finish.
func (cleaner *RetentionCleaner) Stop(ctx context.Context) error {
	cleaner.mu.Lock()
	if !cleaner.started {
		cleaner.mu.Unlock()
		return nil
	}
	cleaner.started = false
	cancel := cleaner.cancel
	cleaner.mu.Unlock()
	cancel()

	done := make(chan struct{})
	go func() {
		cleaner.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (cleaner *RetentionCleaner) loop(ctx context.Context) {
	defer cleaner.wg.Done()
	ticker := time.NewTicker(cleaner.interval)
	defer ticker.Stop()
	for {
		cleaner.run(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (cleaner *RetentionCleaner) run(ctx context.Context) {
	cutoff := cleaner.now().UTC().Add(-cleaner.retention)
	deleted, err := cleaner.cleanup(ctx, cutoff)
	if err != nil && !errors.Is(err, context.Canceled) {
		cleaner.logger.Error("clean scheduled task sessions", "error", err)
		return
	}
	if deleted > 0 {
		cleaner.logger.Info("cleaned scheduled task sessions", "deleted", deleted, "cutoff", cutoff)
	}
}
