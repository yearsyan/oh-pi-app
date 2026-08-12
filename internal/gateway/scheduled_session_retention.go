package gateway

import (
	"errors"
	"fmt"
	"log/slog"

	"github.com/yearsyan/oh-pi-app/internal/scheduledtask"
)

func markExistingScheduledSessions(
	sessions *sessionStore,
	tasks *scheduledtask.Store,
	logger *slog.Logger,
) error {
	listed, err := tasks.List()
	if err != nil {
		return fmt.Errorf("list scheduled tasks for session migration: %w", err)
	}
	seen := make(map[string]struct{})
	for _, task := range listed {
		for _, run := range []*scheduledtask.Run{task.CurrentRun, task.LastRun} {
			if run == nil || run.SessionID == "" {
				continue
			}
			if _, duplicate := seen[run.SessionID]; duplicate {
				continue
			}
			seen[run.SessionID] = struct{}{}
			marked, err := sessions.markScheduled(run.SessionID, task.ID)
			if err != nil {
				if errors.Is(err, errSessionNotFound) {
					continue
				}
				return fmt.Errorf("mark scheduled session %q: %w", run.SessionID, err)
			}
			if marked {
				logger.Info("marked existing scheduled task session", "session_id", run.SessionID)
			}
		}
	}
	return nil
}
