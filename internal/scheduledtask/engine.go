package scheduledtask

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"
)

const (
	defaultMaxConcurrent = 2
	maximumTimerSleep    = 24 * time.Hour
)

// Reporter lets an execution adapter persist the session id as soon as it is
// allocated, before a potentially long model turn completes.
type Reporter interface {
	SetSessionID(string)
}

// Runner turns one claimed occurrence into application work. Returning nil
// marks the run successful; cancellation marks it interrupted; other errors
// are persisted as failures.
type Runner interface {
	Run(context.Context, Task, Run, Reporter) error
}

// RunnerFunc adapts a function to Runner.
type RunnerFunc func(context.Context, Task, Run, Reporter) error

// Run implements Runner.
func (function RunnerFunc) Run(ctx context.Context, task Task, run Run, reporter Reporter) error {
	return function(ctx, task, run, reporter)
}

// Engine owns the timer loop and bounded execution workers for a Store.
type Engine struct {
	store  *Store
	runner Runner
	logger *slog.Logger
	now    func() time.Time
	limit  chan struct{}
	wake   chan struct{}

	mu      sync.Mutex
	started bool
	ctx     context.Context
	cancel  context.CancelFunc
	wg      sync.WaitGroup
}

// NewEngine constructs a scheduler with a two-run global concurrency limit.
func NewEngine(store *Store, runner Runner, logger *slog.Logger) *Engine {
	if logger == nil {
		logger = slog.Default()
	}
	return &Engine{
		store:  store,
		runner: runner,
		logger: logger,
		now:    time.Now,
		limit:  make(chan struct{}, defaultMaxConcurrent),
		wake:   make(chan struct{}, 1),
	}
}

// Start recovers interrupted claims and begins the timer loop.
func (engine *Engine) Start() error {
	engine.mu.Lock()
	defer engine.mu.Unlock()
	if engine.started {
		return nil
	}
	if engine.store == nil || engine.runner == nil {
		return errors.New("scheduled task engine requires a store and runner")
	}
	if err := engine.store.Recover(); err != nil {
		return err
	}
	engine.ctx, engine.cancel = context.WithCancel(context.Background())
	engine.started = true
	engine.wg.Add(1)
	go engine.loop()
	return nil
}

// Stop prevents new claims, cancels active runners, and waits for their
// adapters to observe cancellation.
func (engine *Engine) Stop(ctx context.Context) error {
	engine.mu.Lock()
	if !engine.started {
		engine.mu.Unlock()
		return nil
	}
	engine.started = false
	cancel := engine.cancel
	engine.mu.Unlock()
	cancel()
	engine.Wake()
	done := make(chan struct{})
	go func() {
		engine.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// Wake asks the timer loop to reload task state after an API mutation.
func (engine *Engine) Wake() {
	select {
	case engine.wake <- struct{}{}:
	default:
	}
}

// Create adds a task and wakes the timer loop.
func (engine *Engine) Create(definition Definition) (Task, error) {
	task, err := engine.store.Create(definition)
	if err == nil {
		engine.Wake()
	}
	return task, err
}

// List returns every persisted task.
func (engine *Engine) List() ([]Task, error) {
	return engine.store.List()
}

// Get returns one task.
func (engine *Engine) Get(id string) (Task, error) {
	return engine.store.Get(id)
}

// Update replaces one task definition and re-arms scheduling.
func (engine *Engine) Update(id string, definition Definition) (Task, error) {
	task, err := engine.store.Update(id, definition)
	if err == nil {
		engine.Wake()
	}
	return task, err
}

// SetEnabled pauses or resumes one task.
func (engine *Engine) SetEnabled(id string, enabled bool) (Task, error) {
	task, err := engine.store.SetEnabled(id, enabled)
	if err == nil {
		engine.Wake()
	}
	return task, err
}

// DisableWorkspace pauses all tasks belonging to a workspace.
func (engine *Engine) DisableWorkspace(workspaceID string) error {
	err := engine.store.DisableWorkspace(workspaceID)
	if err == nil {
		engine.Wake()
	}
	return err
}

// Delete removes one idle task.
func (engine *Engine) Delete(id string) error {
	err := engine.store.Delete(id)
	if err == nil {
		engine.Wake()
	}
	return err
}

// RunNow claims one manual occurrence without changing its future schedule.
func (engine *Engine) RunNow(id string) (Task, Run, error) {
	task, run, err := engine.store.claimManual(id)
	if err != nil {
		return Task{}, Run{}, err
	}
	if err := engine.dispatch(task, run); err != nil {
		_ = engine.store.complete(task.ID, run.ID, RunInterrupted, err.Error())
		return Task{}, Run{}, err
	}
	return task, run, nil
}

// TriggerEvent claims one HTTP-triggered occurrence by its secret event key.
// The canonical JSON event data is passed only to the in-memory runner and is
// deliberately excluded from persisted task state. A positive delay makes the
// occurrence wait until its scheduled time without occupying a worker slot.
func (engine *Engine) TriggerEvent(eventKey, eventData string, delay time.Duration) (Task, Run, error) {
	task, run, err := engine.store.claimEvent(eventKey, eventData, delay)
	if err != nil {
		return Task{}, Run{}, err
	}
	if err := engine.dispatch(task, run); err != nil {
		_ = engine.store.complete(task.ID, run.ID, RunInterrupted, err.Error())
		return Task{}, Run{}, err
	}
	return task, run, nil
}

func (engine *Engine) loop() {
	defer engine.wg.Done()
	for {
		select {
		case <-engine.ctx.Done():
			return
		default:
		}

		tasks, err := engine.store.List()
		if err != nil {
			engine.logger.Error("load scheduled tasks", "error", err)
			if !engine.wait(time.Second) {
				return
			}
			continue
		}
		now := engine.now().UTC()
		var next *time.Time
		dispatched := false
		retrySoon := false
		for _, task := range tasks {
			if !task.Enabled || task.NextRunAt == nil {
				continue
			}
			if task.NextRunAt.After(now) {
				if next == nil || task.NextRunAt.Before(*next) {
					value := *task.NextRunAt
					next = &value
				}
				continue
			}
			claimedTask, run, claimErr := engine.store.claimDue(task.ID, *task.NextRunAt)
			switch {
			case claimErr == nil:
				if err := engine.dispatch(claimedTask, run); err != nil {
					_ = engine.store.complete(task.ID, run.ID, RunInterrupted, err.Error())
				}
				dispatched = true
			case errors.Is(claimErr, ErrRunning), errors.Is(claimErr, ErrNotDue):
				dispatched = true
			default:
				engine.logger.Error("claim scheduled task", "task_id", task.ID, "error", claimErr)
				retrySoon = true
			}
		}
		if dispatched {
			continue
		}
		if retrySoon {
			if !engine.wait(time.Second) {
				return
			}
			continue
		}
		delay := maximumTimerSleep
		if next != nil {
			delay = next.Sub(now)
			if delay < 0 {
				delay = 0
			}
			if delay > maximumTimerSleep {
				delay = maximumTimerSleep
			}
		}
		if !engine.wait(delay) {
			return
		}
	}
}

func (engine *Engine) wait(delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-engine.ctx.Done():
		return false
	case <-engine.wake:
		return true
	case <-timer.C:
		return true
	}
}

func (engine *Engine) dispatch(task Task, run Run) error {
	engine.mu.Lock()
	if !engine.started || engine.ctx == nil {
		engine.mu.Unlock()
		return errors.New("scheduled task engine is not running")
	}
	ctx := engine.ctx
	engine.wg.Add(1)
	engine.mu.Unlock()
	go engine.execute(ctx, task, run)
	return nil
}

func (engine *Engine) execute(ctx context.Context, task Task, run Run) {
	defer engine.wg.Done()
	if delay := time.Until(run.ScheduledFor); delay > 0 {
		timer := time.NewTimer(delay)
		select {
		case <-timer.C:
		case <-ctx.Done():
			if !timer.Stop() {
				select {
				case <-timer.C:
				default:
				}
			}
			_ = engine.store.complete(task.ID, run.ID, RunInterrupted, "gateway is shutting down")
			return
		}
	}
	select {
	case engine.limit <- struct{}{}:
		defer func() { <-engine.limit }()
	case <-ctx.Done():
		_ = engine.store.complete(task.ID, run.ID, RunInterrupted, "gateway is shutting down")
		return
	}

	reporter := runReporter{store: engine.store, taskID: task.ID, runID: run.ID, logger: engine.logger}
	failure := runSafely(ctx, engine.runner, task, run, reporter)
	status := RunSucceeded
	message := ""
	if failure != nil {
		message = failure.Error()
		if ctx.Err() != nil || errors.Is(failure, context.Canceled) {
			status = RunInterrupted
		} else {
			status = RunFailed
		}
	}
	if err := engine.store.complete(task.ID, run.ID, status, message); err != nil && !errors.Is(err, ErrNotDue) {
		engine.logger.Error("complete scheduled task", "task_id", task.ID, "run_id", run.ID, "error", err)
	}
	engine.Wake()
}

func runSafely(ctx context.Context, runner Runner, task Task, run Run, reporter Reporter) (failure error) {
	defer func() {
		if recovered := recover(); recovered != nil {
			failure = fmt.Errorf("scheduled task runner panicked: %v", recovered)
		}
	}()
	return runner.Run(ctx, task, run, reporter)
}

type runReporter struct {
	store         *Store
	taskID, runID string
	logger        *slog.Logger
}

func (reporter runReporter) SetSessionID(sessionID string) {
	if sessionID == "" {
		return
	}
	if err := reporter.store.markSession(reporter.taskID, reporter.runID, sessionID); err != nil {
		reporter.logger.Warn(
			"persist scheduled task session",
			"task_id", reporter.taskID,
			"run_id", reporter.runID,
			"error", err,
		)
	}
}
