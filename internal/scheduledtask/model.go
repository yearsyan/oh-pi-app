package scheduledtask

import (
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	maxTaskNameRunes       = 200
	maxPromptBytes         = 64 << 10
	maxTaskSkillEntries    = 32
	maxTaskSkillEntryBytes = 4 << 10
)

var (
	// ErrNotFound is returned when a task id has no persisted definition.
	ErrNotFound = errors.New("scheduled task not found")
	// ErrRunning is returned when an operation conflicts with an active run.
	ErrRunning = errors.New("scheduled task is running")
	// ErrNotDue indicates that a stale scheduler entry no longer matches a task.
	ErrNotDue = errors.New("scheduled task is not due")
	// ErrNoFutureOccurrence indicates an enabled schedule that can never fire again.
	ErrNoFutureOccurrence = errors.New("scheduled task has no future occurrence")
)

// RunStatus is the durable outcome of one task occurrence.
type RunStatus string

const (
	RunRunning     RunStatus = "running"
	RunSucceeded   RunStatus = "succeeded"
	RunFailed      RunStatus = "failed"
	RunInterrupted RunStatus = "interrupted"
	RunSkipped     RunStatus = "skipped"
)

// Run describes the current or most recently completed occurrence.
type Run struct {
	ID           string     `json:"id"`
	ScheduledFor time.Time  `json:"scheduled_for"`
	StartedAt    time.Time  `json:"started_at"`
	FinishedAt   *time.Time `json:"finished_at,omitempty"`
	Status       RunStatus  `json:"status"`
	SessionID    string     `json:"session_id,omitempty"`
	Error        string     `json:"error,omitempty"`
	Manual       bool       `json:"manual,omitempty"`
}

// Definition contains all user-editable task fields.
type Definition struct {
	Name        string   `json:"name"`
	WorkspaceID string   `json:"workspace_id"`
	Model       string   `json:"model,omitempty"`
	Thinking    string   `json:"thinking,omitempty"`
	SkillPaths  []string `json:"skill_paths,omitempty"`
	NoSkills    bool     `json:"no_skills,omitempty"`
	Prompt      string   `json:"prompt"`
	Schedule    Schedule `json:"schedule"`
	Enabled     bool     `json:"enabled"`
}

// Task is the persisted task definition plus scheduler-owned state.
type Task struct {
	ID          string     `json:"id"`
	Name        string     `json:"name"`
	WorkspaceID string     `json:"workspace_id"`
	Model       string     `json:"model,omitempty"`
	Thinking    string     `json:"thinking,omitempty"`
	SkillPaths  []string   `json:"skill_paths,omitempty"`
	NoSkills    bool       `json:"no_skills,omitempty"`
	Prompt      string     `json:"prompt"`
	Schedule    Schedule   `json:"schedule"`
	Enabled     bool       `json:"enabled"`
	NextRunAt   *time.Time `json:"next_run_at,omitempty"`
	CurrentRun  *Run       `json:"current_run,omitempty"`
	LastRun     *Run       `json:"last_run,omitempty"`
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
}

// Validate checks a task definition without mutating persistent state.
func (definition Definition) Validate() error {
	_, err := normalizeDefinition(definition)
	return err
}

// Definition returns the user-editable portion of a task.
func (task Task) Definition() Definition {
	return definitionFromTask(task)
}

func normalizeDefinition(definition Definition) (Definition, error) {
	definition.Name = strings.TrimSpace(definition.Name)
	definition.WorkspaceID = strings.TrimSpace(definition.WorkspaceID)
	definition.Model = strings.TrimSpace(definition.Model)
	definition.Thinking = strings.TrimSpace(definition.Thinking)
	definition.Prompt = strings.TrimSpace(definition.Prompt)
	definition.Schedule = definition.Schedule.normalized()
	var err error
	definition.SkillPaths, err = normalizeSkillPaths(definition.SkillPaths)
	if err != nil {
		return Definition{}, err
	}

	if definition.Name == "" {
		return Definition{}, errors.New("task name is required")
	}
	if !utf8.ValidString(definition.Name) || utf8.RuneCountInString(definition.Name) > maxTaskNameRunes {
		return Definition{}, fmt.Errorf("task name must not exceed %d characters", maxTaskNameRunes)
	}
	if definition.WorkspaceID == "" {
		return Definition{}, errors.New("workspace_id is required")
	}
	if definition.Prompt == "" {
		return Definition{}, errors.New("prompt is required")
	}
	if !utf8.ValidString(definition.Prompt) || len(definition.Prompt) > maxPromptBytes {
		return Definition{}, fmt.Errorf("prompt must be valid UTF-8 and not exceed %d bytes", maxPromptBytes)
	}
	if len(definition.Model) > 512 || len(definition.Thinking) > 64 {
		return Definition{}, errors.New("model configuration is too long")
	}
	if _, err := definition.Schedule.parse(); err != nil {
		return Definition{}, err
	}
	return definition, nil
}

func normalizeSkillPaths(paths []string) ([]string, error) {
	if len(paths) > maxTaskSkillEntries {
		return nil, fmt.Errorf("skill_paths must not contain more than %d entries", maxTaskSkillEntries)
	}
	normalized := make([]string, 0, len(paths))
	seen := make(map[string]struct{}, len(paths))
	for _, path := range paths {
		path = strings.TrimSpace(path)
		if path == "" {
			return nil, errors.New("skill_paths entries must not be empty")
		}
		if !utf8.ValidString(path) || len(path) > maxTaskSkillEntryBytes || strings.ContainsRune(path, '\x00') {
			return nil, fmt.Errorf(
				"skill_paths entries must be valid UTF-8, contain no NUL byte, and not exceed %d bytes",
				maxTaskSkillEntryBytes,
			)
		}
		if _, exists := seen[path]; exists {
			continue
		}
		seen[path] = struct{}{}
		normalized = append(normalized, path)
	}
	return normalized, nil
}

func definitionFromTask(task Task) Definition {
	return Definition{
		Name:        task.Name,
		WorkspaceID: task.WorkspaceID,
		Model:       task.Model,
		Thinking:    task.Thinking,
		SkillPaths:  append([]string(nil), task.SkillPaths...),
		NoSkills:    task.NoSkills,
		Prompt:      task.Prompt,
		Schedule:    task.Schedule,
		Enabled:     task.Enabled,
	}
}

func applyDefinition(task *Task, definition Definition) {
	task.Name = definition.Name
	task.WorkspaceID = definition.WorkspaceID
	task.Model = definition.Model
	task.Thinking = definition.Thinking
	task.SkillPaths = append([]string(nil), definition.SkillPaths...)
	task.NoSkills = definition.NoSkills
	task.Prompt = definition.Prompt
	task.Schedule = definition.Schedule
	task.Enabled = definition.Enabled
}
