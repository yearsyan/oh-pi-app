package scheduledtask

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

const (
	taskFileName  = "task.json"
	eventKeyBytes = 16
	eventKeySize  = eventKeyBytes * 2
)

// Store persists each task as an independently replaceable JSON document.
// One gateway process owns a data directory at a time, so an in-process mutex
// serializes mutations while atomic rename protects crash recovery.
type Store struct {
	root string
	now  func() time.Time
	mu   sync.RWMutex
}

// NewStore opens the scheduled-task store below dataDir.
func NewStore(dataDir string) (*Store, error) {
	return newStore(filepath.Join(dataDir, "scheduled-tasks"), time.Now)
}

func newStore(root string, now func() time.Time) (*Store, error) {
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, fmt.Errorf("create scheduled task store: %w", err)
	}
	return &Store{root: root, now: now}, nil
}

// Create persists a new task and calculates its first occurrence.
func (store *Store) Create(definition Definition) (Task, error) {
	normalized, err := normalizeDefinition(definition)
	if err != nil {
		return Task{}, err
	}
	now := store.now().UTC()
	var next *time.Time
	if normalized.Enabled {
		next, err = firstOccurrence(normalized.Schedule, now)
		if err != nil {
			return Task{}, err
		}
	}

	store.mu.Lock()
	defer store.mu.Unlock()
	var eventKey string
	if normalized.Schedule.Kind == ScheduleHTTP {
		eventKey, err = store.allocateEventKeyLocked()
		if err != nil {
			return Task{}, err
		}
	}
	for range 8 {
		id, err := newID()
		if err != nil {
			return Task{}, err
		}
		dir := store.taskDir(id)
		if err := os.Mkdir(dir, 0o700); err != nil {
			if errors.Is(err, fs.ErrExist) {
				continue
			}
			return Task{}, fmt.Errorf("create scheduled task directory: %w", err)
		}
		task := Task{
			ID: id, CreatedAt: now, UpdatedAt: now, NextRunAt: next, EventKey: eventKey,
		}
		applyDefinition(&task, normalized)
		if err := writeNewTask(dir, task); err != nil {
			_ = os.Remove(dir)
			return Task{}, err
		}
		return cloneTask(task), nil
	}
	return Task{}, errors.New("could not allocate a unique scheduled task id")
}

// Get loads one task by id.
func (store *Store) Get(id string) (Task, error) {
	store.mu.RLock()
	defer store.mu.RUnlock()
	return store.loadLocked(id)
}

// List loads all tasks, ordered by next occurrence and then name.
func (store *Store) List() ([]Task, error) {
	store.mu.RLock()
	defer store.mu.RUnlock()
	entries, err := os.ReadDir(store.root)
	if err != nil {
		return nil, fmt.Errorf("read scheduled task store: %w", err)
	}
	tasks := make([]Task, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() || !validID(entry.Name()) {
			continue
		}
		task, err := store.loadLocked(entry.Name())
		if err != nil {
			return nil, fmt.Errorf("load scheduled task %q: %w", entry.Name(), err)
		}
		tasks = append(tasks, task)
	}
	sort.SliceStable(tasks, func(left, right int) bool {
		leftNext, rightNext := tasks[left].NextRunAt, tasks[right].NextRunAt
		if leftNext != nil && rightNext != nil && !leftNext.Equal(*rightNext) {
			return leftNext.Before(*rightNext)
		}
		if (leftNext != nil) != (rightNext != nil) {
			return leftNext != nil
		}
		if tasks[left].Name != tasks[right].Name {
			return tasks[left].Name < tasks[right].Name
		}
		return tasks[left].ID < tasks[right].ID
	})
	return tasks, nil
}

// Update replaces every user-editable field while preserving run history.
func (store *Store) Update(id string, definition Definition) (Task, error) {
	normalized, err := normalizeDefinition(definition)
	if err != nil {
		return Task{}, err
	}
	now := store.now().UTC()
	var next *time.Time
	if normalized.Enabled {
		next, err = firstOccurrence(normalized.Schedule, now)
		if err != nil {
			return Task{}, err
		}
	}

	store.mu.Lock()
	defer store.mu.Unlock()
	task, err := store.loadLocked(id)
	if err != nil {
		return Task{}, err
	}
	if task.CurrentRun != nil {
		return Task{}, ErrRunning
	}
	wasHTTP := task.Schedule.Kind == ScheduleHTTP
	applyDefinition(&task, normalized)
	switch {
	case task.Schedule.Kind == ScheduleHTTP && !wasHTTP:
		task.EventKey, err = store.allocateEventKeyLocked()
		if err != nil {
			return Task{}, err
		}
	case task.Schedule.Kind != ScheduleHTTP:
		task.EventKey = ""
	}
	task.NextRunAt = next
	task.UpdatedAt = now
	if err := replaceTask(store.taskDir(id), task); err != nil {
		return Task{}, err
	}
	return cloneTask(task), nil
}

// SetEnabled pauses or resumes one task without changing its definition.
func (store *Store) SetEnabled(id string, enabled bool) (Task, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	task, err := store.loadLocked(id)
	if err != nil {
		return Task{}, err
	}
	if task.Enabled == enabled {
		return task, nil
	}
	now := store.now().UTC()
	var next *time.Time
	if enabled {
		next, err = firstOccurrence(task.Schedule, now)
		if err != nil {
			return Task{}, err
		}
	}
	task.Enabled = enabled
	task.NextRunAt = next
	task.UpdatedAt = now
	if err := replaceTask(store.taskDir(id), task); err != nil {
		return Task{}, err
	}
	return cloneTask(task), nil
}

// DisableWorkspace prevents hidden or deleted workspaces from continuing to
// launch unattended work. An already-running occurrence is not interrupted.
func (store *Store) DisableWorkspace(workspaceID string) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	entries, err := os.ReadDir(store.root)
	if err != nil {
		return fmt.Errorf("read scheduled task store: %w", err)
	}
	now := store.now().UTC()
	for _, entry := range entries {
		if !entry.IsDir() || !validID(entry.Name()) {
			continue
		}
		task, err := store.loadLocked(entry.Name())
		if err != nil {
			return err
		}
		if task.WorkspaceID != workspaceID || !task.Enabled {
			continue
		}
		task.Enabled = false
		task.NextRunAt = nil
		task.UpdatedAt = now
		if err := replaceTask(store.taskDir(task.ID), task); err != nil {
			return err
		}
	}
	return nil
}

// Delete permanently removes a task definition. Completed sessions are owned
// by the gateway session store and are deliberately retained.
func (store *Store) Delete(id string) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	task, err := store.loadLocked(id)
	if err != nil {
		return err
	}
	if task.CurrentRun != nil {
		return ErrRunning
	}
	if err := os.RemoveAll(store.taskDir(id)); err != nil {
		return fmt.Errorf("delete scheduled task: %w", err)
	}
	return nil
}

func (store *Store) claimDue(id string, expected time.Time) (Task, Run, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	task, err := store.loadLocked(id)
	if err != nil {
		return Task{}, Run{}, err
	}
	if !task.Enabled || task.NextRunAt == nil || !task.NextRunAt.Equal(expected) {
		return Task{}, Run{}, ErrNotDue
	}
	now := store.now().UTC()
	if task.NextRunAt.After(now) {
		return Task{}, Run{}, ErrNotDue
	}
	if task.CurrentRun != nil {
		next, nextErr := nextAfterClaim(task.Schedule, now)
		if nextErr != nil {
			return Task{}, Run{}, nextErr
		}
		runID, idErr := newID()
		if idErr != nil {
			return Task{}, Run{}, idErr
		}
		finished := now
		skipped := Run{
			ID:           runID,
			ScheduledFor: expected.UTC(),
			StartedAt:    now,
			FinishedAt:   &finished,
			Status:       RunSkipped,
			Error:        "previous occurrence is still running",
		}
		task.LastRun = &skipped
		task.NextRunAt = next
		task.UpdatedAt = now
		if err := replaceTask(store.taskDir(id), task); err != nil {
			return Task{}, Run{}, err
		}
		return Task{}, Run{}, ErrRunning
	}

	runID, err := newID()
	if err != nil {
		return Task{}, Run{}, err
	}
	run := Run{
		ID:           runID,
		ScheduledFor: expected.UTC(),
		StartedAt:    now,
		Status:       RunRunning,
	}
	task.CurrentRun = &run
	task.NextRunAt, err = nextAfterClaim(task.Schedule, now)
	if err != nil {
		return Task{}, Run{}, err
	}
	if task.Schedule.Kind == ScheduleOnce {
		task.Enabled = false
	}
	task.UpdatedAt = now
	if err := replaceTask(store.taskDir(id), task); err != nil {
		return Task{}, Run{}, err
	}
	return cloneTask(task), run, nil
}

func (store *Store) claimManual(id string) (Task, Run, error) {
	store.mu.Lock()
	defer store.mu.Unlock()
	task, err := store.loadLocked(id)
	if err != nil {
		return Task{}, Run{}, err
	}
	if task.CurrentRun != nil {
		return Task{}, Run{}, ErrRunning
	}
	now := store.now().UTC()
	runID, err := newID()
	if err != nil {
		return Task{}, Run{}, err
	}
	run := Run{ID: runID, ScheduledFor: now, StartedAt: now, Status: RunRunning, Manual: true}
	task.CurrentRun = &run
	task.UpdatedAt = now
	if err := replaceTask(store.taskDir(id), task); err != nil {
		return Task{}, Run{}, err
	}
	return cloneTask(task), run, nil
}

func (store *Store) claimEvent(eventKey, eventData string, delay time.Duration) (Task, Run, error) {
	if eventData == "" {
		return Task{}, Run{}, errors.New("HTTP trigger event data is required")
	}
	if delay < 0 {
		return Task{}, Run{}, errors.New("HTTP trigger delay must not be negative")
	}
	store.mu.Lock()
	defer store.mu.Unlock()
	task, err := store.findEventTaskLocked(eventKey)
	if err != nil {
		return Task{}, Run{}, err
	}
	if !task.Enabled {
		return Task{}, Run{}, ErrDisabled
	}
	if task.CurrentRun != nil {
		return Task{}, Run{}, ErrRunning
	}
	now := store.now().UTC()
	runID, err := newID()
	if err != nil {
		return Task{}, Run{}, err
	}
	run := Run{
		ID:           runID,
		ScheduledFor: now.Add(delay),
		StartedAt:    now,
		Status:       RunRunning,
		EventData:    eventData,
	}
	task.CurrentRun = &run
	task.UpdatedAt = now
	if err := replaceTask(store.taskDir(task.ID), task); err != nil {
		return Task{}, Run{}, err
	}
	return cloneTask(task), run, nil
}

func (store *Store) markSession(id, runID, sessionID string) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	task, err := store.loadLocked(id)
	if err != nil {
		return err
	}
	if task.CurrentRun == nil || task.CurrentRun.ID != runID {
		return ErrNotDue
	}
	task.CurrentRun.SessionID = sessionID
	task.UpdatedAt = store.now().UTC()
	return replaceTask(store.taskDir(id), task)
}

func (store *Store) complete(id, runID string, status RunStatus, message string) error {
	store.mu.Lock()
	defer store.mu.Unlock()
	task, err := store.loadLocked(id)
	if err != nil {
		return err
	}
	if task.CurrentRun == nil || task.CurrentRun.ID != runID {
		return ErrNotDue
	}
	now := store.now().UTC()
	completed := *task.CurrentRun
	completed.Status = status
	completed.Error = message
	completed.FinishedAt = &now
	task.CurrentRun = nil
	task.LastRun = &completed
	task.UpdatedAt = now
	return replaceTask(store.taskDir(id), task)
}

// Recover marks claims left by a previous gateway process as interrupted. The
// already-advanced next occurrence prevents an automatic duplicate execution.
func (store *Store) Recover() error {
	store.mu.Lock()
	defer store.mu.Unlock()
	entries, err := os.ReadDir(store.root)
	if err != nil {
		return fmt.Errorf("read scheduled task store: %w", err)
	}
	now := store.now().UTC()
	for _, entry := range entries {
		if !entry.IsDir() || !validID(entry.Name()) {
			continue
		}
		task, err := store.loadLocked(entry.Name())
		if err != nil {
			return err
		}
		if task.CurrentRun == nil {
			continue
		}
		interrupted := *task.CurrentRun
		interrupted.Status = RunInterrupted
		interrupted.Error = "gateway restarted before the run completed"
		interrupted.FinishedAt = &now
		task.CurrentRun = nil
		task.LastRun = &interrupted
		task.UpdatedAt = now
		if err := replaceTask(store.taskDir(task.ID), task); err != nil {
			return err
		}
	}
	return nil
}

func firstOccurrence(schedule Schedule, now time.Time) (*time.Time, error) {
	if schedule.Kind == ScheduleHTTP {
		return nil, nil
	}
	next, err := schedule.Next(now)
	if err != nil {
		return nil, err
	}
	if next.IsZero() {
		return nil, ErrNoFutureOccurrence
	}
	return &next, nil
}

func nextAfterClaim(schedule Schedule, now time.Time) (*time.Time, error) {
	if schedule.Kind == ScheduleOnce || schedule.Kind == ScheduleHTTP {
		return nil, nil
	}
	next, err := schedule.Next(now)
	if err != nil {
		return nil, err
	}
	if next.IsZero() {
		return nil, ErrNoFutureOccurrence
	}
	return &next, nil
}

func (store *Store) taskDir(id string) string {
	return filepath.Join(store.root, id)
}

func (store *Store) loadLocked(id string) (Task, error) {
	if !validID(id) {
		return Task{}, ErrNotFound
	}
	dir := store.taskDir(id)
	info, err := os.Lstat(dir)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return Task{}, ErrNotFound
		}
		return Task{}, fmt.Errorf("inspect scheduled task directory: %w", err)
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return Task{}, ErrNotFound
	}
	path := filepath.Join(dir, taskFileName)
	fileInfo, err := os.Lstat(path)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return Task{}, ErrNotFound
		}
		return Task{}, fmt.Errorf("inspect scheduled task metadata: %w", err)
	}
	if !fileInfo.Mode().IsRegular() {
		return Task{}, ErrNotFound
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return Task{}, fmt.Errorf("read scheduled task metadata: %w", err)
	}
	var task Task
	if err := json.Unmarshal(data, &task); err != nil {
		return Task{}, fmt.Errorf("decode scheduled task metadata: %w", err)
	}
	if task.ID != id || task.CreatedAt.IsZero() || task.UpdatedAt.IsZero() {
		return Task{}, errors.New("invalid scheduled task metadata")
	}
	if _, err := normalizeDefinition(definitionFromTask(task)); err != nil {
		return Task{}, fmt.Errorf("invalid scheduled task definition: %w", err)
	}
	if task.Schedule.Kind == ScheduleHTTP {
		if !validEventKey(task.EventKey) {
			return Task{}, errors.New("invalid scheduled task event key")
		}
	} else if task.EventKey != "" {
		return Task{}, errors.New("unexpected scheduled task event key")
	}
	return cloneTask(task), nil
}

func (store *Store) allocateEventKeyLocked() (string, error) {
	for range 8 {
		key, err := newEventKey()
		if err != nil {
			return "", err
		}
		exists, err := store.eventKeyExistsLocked(key)
		if err != nil {
			return "", err
		}
		if !exists {
			return key, nil
		}
	}
	return "", errors.New("could not allocate a unique scheduled task event key")
}

func (store *Store) eventKeyExistsLocked(eventKey string) (bool, error) {
	entries, err := os.ReadDir(store.root)
	if err != nil {
		return false, fmt.Errorf("read scheduled task store: %w", err)
	}
	for _, entry := range entries {
		if !entry.IsDir() || !validID(entry.Name()) {
			continue
		}
		task, err := store.loadLocked(entry.Name())
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return false, err
		}
		if task.EventKey == eventKey {
			return true, nil
		}
	}
	return false, nil
}

func (store *Store) findEventTaskLocked(eventKey string) (Task, error) {
	if !validEventKey(eventKey) {
		return Task{}, ErrNotFound
	}
	entries, err := os.ReadDir(store.root)
	if err != nil {
		return Task{}, fmt.Errorf("read scheduled task store: %w", err)
	}
	var found *Task
	for _, entry := range entries {
		if !entry.IsDir() || !validID(entry.Name()) {
			continue
		}
		task, err := store.loadLocked(entry.Name())
		if err != nil {
			return Task{}, err
		}
		if subtle.ConstantTimeCompare([]byte(task.EventKey), []byte(eventKey)) == 1 {
			value := task
			found = &value
		}
	}
	if found == nil || found.Schedule.Kind != ScheduleHTTP {
		return Task{}, ErrNotFound
	}
	return cloneTask(*found), nil
}

func writeNewTask(dir string, task Task) error {
	data, err := encodeTask(task)
	if err != nil {
		return err
	}
	path := filepath.Join(dir, taskFileName)
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return fmt.Errorf("create scheduled task metadata: %w", err)
	}
	if _, err := file.Write(data); err != nil {
		_ = file.Close()
		_ = os.Remove(path)
		return fmt.Errorf("write scheduled task metadata: %w", err)
	}
	if err := file.Sync(); err != nil {
		_ = file.Close()
		_ = os.Remove(path)
		return fmt.Errorf("sync scheduled task metadata: %w", err)
	}
	if err := file.Close(); err != nil {
		_ = os.Remove(path)
		return fmt.Errorf("close scheduled task metadata: %w", err)
	}
	return nil
}

func replaceTask(dir string, task Task) error {
	data, err := encodeTask(task)
	if err != nil {
		return err
	}
	temporary, err := os.CreateTemp(dir, ".scheduled-task-*.tmp")
	if err != nil {
		return fmt.Errorf("create temporary scheduled task metadata: %w", err)
	}
	path := temporary.Name()
	committed := false
	defer func() {
		_ = temporary.Close()
		if !committed {
			_ = os.Remove(path)
		}
	}()
	if err := temporary.Chmod(0o600); err != nil {
		return fmt.Errorf("set scheduled task metadata permissions: %w", err)
	}
	if _, err := temporary.Write(data); err != nil {
		return fmt.Errorf("write temporary scheduled task metadata: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		return fmt.Errorf("sync temporary scheduled task metadata: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close temporary scheduled task metadata: %w", err)
	}
	if err := os.Rename(path, filepath.Join(dir, taskFileName)); err != nil {
		return fmt.Errorf("replace scheduled task metadata: %w", err)
	}
	committed = true
	return nil
}

func encodeTask(task Task) ([]byte, error) {
	data, err := json.MarshalIndent(task, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("encode scheduled task metadata: %w", err)
	}
	return append(data, '\n'), nil
}

func cloneTask(task Task) Task {
	task.SkillPaths = append([]string(nil), task.SkillPaths...)
	if task.NextRunAt != nil {
		value := *task.NextRunAt
		task.NextRunAt = &value
	}
	if task.Schedule.AnchorAt != nil {
		value := *task.Schedule.AnchorAt
		task.Schedule.AnchorAt = &value
	}
	if task.Schedule.At != nil {
		value := *task.Schedule.At
		task.Schedule.At = &value
	}
	task.CurrentRun = cloneRun(task.CurrentRun)
	task.LastRun = cloneRun(task.LastRun)
	return task
}

func cloneRun(run *Run) *Run {
	if run == nil {
		return nil
	}
	copy := *run
	if copy.FinishedAt != nil {
		value := *copy.FinishedAt
		copy.FinishedAt = &value
	}
	return &copy
}

func newID() (string, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return "", fmt.Errorf("generate scheduled task id: %w", err)
	}
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80
	var text [36]byte
	hex.Encode(text[0:8], id[0:4])
	text[8] = '-'
	hex.Encode(text[9:13], id[4:6])
	text[13] = '-'
	hex.Encode(text[14:18], id[6:8])
	text[18] = '-'
	hex.Encode(text[19:23], id[8:10])
	text[23] = '-'
	hex.Encode(text[24:36], id[10:16])
	return string(text[:]), nil
}

func newEventKey() (string, error) {
	var random [eventKeyBytes]byte
	if _, err := rand.Read(random[:]); err != nil {
		return "", fmt.Errorf("generate scheduled task event key: %w", err)
	}
	var text [eventKeySize]byte
	hex.Encode(text[:], random[:])
	return string(text[:]), nil
}

func validEventKey(eventKey string) bool {
	if len(eventKey) != eventKeySize {
		return false
	}
	for _, value := range []byte(eventKey) {
		if !((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')) {
			return false
		}
	}
	return true
}

func validID(id string) bool {
	if len(id) != 36 {
		return false
	}
	for index, value := range []byte(id) {
		switch index {
		case 8, 13, 18, 23:
			if value != '-' {
				return false
			}
		default:
			if !((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')) {
				return false
			}
		}
	}
	return true
}
