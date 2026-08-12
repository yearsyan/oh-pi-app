package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"
)

var (
	errSessionDeleting   = errors.New("session is being deleted")
	errSessionOutputting = errors.New("session is outputting")
)

type managedSession struct {
	Metadata   sessionMetadata
	Running    bool
	Outputting bool
}

type sessionManager struct {
	cfg        Config
	store      *sessionStore
	workspaces *workspaceStore
	mu         sync.Mutex
	sessions   map[string]*piSession
	deleting   map[string]struct{}
	closing    bool
	wg         sync.WaitGroup
}

func newSessionManager(cfg Config, store *sessionStore, workspaces *workspaceStore) *sessionManager {
	return &sessionManager{
		cfg:        cfg,
		store:      store,
		workspaces: workspaces,
		sessions:   make(map[string]*piSession),
		deleting:   make(map[string]struct{}),
	}
}

func (m *sessionManager) create(workspaceID string, initial initialSessionConfig) (*piSession, error) {
	return m.createWithSource(workspaceID, initial, "")
}

func (m *sessionManager) createScheduled(
	workspaceID string,
	taskID string,
	initial initialSessionConfig,
) (*piSession, error) {
	workspace, err := m.workspaces.load(workspaceID)
	if err != nil {
		return nil, err
	}
	meta, dir, err := m.store.createForScheduledTask(
		workspace.ID,
		taskID,
		initial.skillPaths,
		initial.noSkills,
	)
	if err != nil {
		return nil, err
	}
	return m.startCreatedSession(workspace, meta, dir, initial)
}

func (m *sessionManager) createWithSource(
	workspaceID string,
	initial initialSessionConfig,
	source string,
) (*piSession, error) {
	workspace, err := m.workspaces.load(workspaceID)
	if err != nil {
		return nil, err
	}
	meta, dir, err := m.store.createWithResources(
		workspace.ID,
		source,
		initial.skillPaths,
		initial.noSkills,
	)
	if err != nil {
		return nil, err
	}
	return m.startCreatedSession(workspace, meta, dir, initial)
}

func (m *sessionManager) startCreatedSession(
	workspace workspaceMetadata,
	meta sessionMetadata,
	dir string,
	initial initialSessionConfig,
) (*piSession, error) {
	args := m.argsForWorkspace(workspace)
	args = append(args, initial.args(workspace.SkillPaths, workspace.NoSkills)...)
	session, _, err := m.getOrStart(meta.ID, dir, workspace.ID, workspace.Directory, args, true)
	if err != nil {
		if discardErr := m.store.discard(meta.ID); discardErr != nil {
			m.cfg.Logger.Warn(
				"discard session after failed start",
				"session_id", meta.ID,
				"error", discardErr,
			)
		}
		return nil, fmt.Errorf("start new session: %w", err)
	}
	session.startIdleTimer()
	return session, nil
}

func (m *sessionManager) listScheduledTask(taskID string) ([]managedSession, error) {
	all, err := m.list()
	if err != nil {
		return nil, err
	}
	result := make([]managedSession, 0)
	for _, session := range all {
		if session.Metadata.ScheduledTaskID == taskID {
			result = append(result, session)
		}
	}
	return result, nil
}

func (m *sessionManager) pruneScheduledSessions(ctx context.Context, cutoff time.Time) (int, error) {
	metas, err := m.store.list()
	if err != nil {
		return 0, err
	}
	deleted := 0
	for _, meta := range metas {
		if err := ctx.Err(); err != nil {
			return deleted, err
		}
		if meta.Source != sessionSourceScheduledTask || !meta.UpdatedAt.Before(cutoff) {
			continue
		}

		m.mu.Lock()
		if _, deleting := m.deleting[meta.ID]; deleting {
			m.mu.Unlock()
			continue
		}
		current := m.sessions[meta.ID]
		if current != nil && !current.isDone() {
			m.mu.Unlock()
			continue
		}
		m.deleting[meta.ID] = struct{}{}
		m.mu.Unlock()

		removed, removeErr := m.store.deleteScheduledBefore(meta.ID, cutoff)
		m.mu.Lock()
		delete(m.deleting, meta.ID)
		if removed {
			delete(m.sessions, meta.ID)
		}
		m.mu.Unlock()
		if removeErr != nil {
			return deleted, removeErr
		}
		if removed {
			deleted++
		}
	}
	return deleted, nil
}

func (m *sessionManager) attach(id string) (*piSession, error) {
	meta, dir, err := m.store.load(id)
	if err != nil {
		return nil, err
	}
	workspace, err := m.workspaces.load(meta.WorkspaceID)
	if err != nil {
		return nil, fmt.Errorf("load session workspace: %w", err)
	}
	args := m.argsForWorkspace(workspace)
	resources := initialSessionConfig{skillPaths: meta.SkillPaths, noSkills: meta.NoSkills}
	args = append(args, resources.args(workspace.SkillPaths, workspace.NoSkills)...)
	session, started, err := m.getOrStart(
		meta.ID,
		dir,
		workspace.ID,
		workspace.Directory,
		args,
		false,
	)
	if err != nil {
		return nil, fmt.Errorf("start existing session: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), m.cfg.HistoryTimeout)
	err = session.ensureHistory(ctx)
	cancel()
	if err != nil {
		session.stop(1000, "could not load session history")
		return nil, fmt.Errorf("load existing session history: %w", err)
	}
	if started && meta.NameSet && meta.Name != "" {
		ctx, cancel := context.WithTimeout(context.Background(), m.cfg.HistoryTimeout)
		err := session.setSessionName(ctx, meta.Name)
		cancel()
		if err != nil {
			session.stop(1000, "could not restore session name")
			return nil, fmt.Errorf("restore existing session name: %w", err)
		}
	}
	session.startIdleTimer()
	return session, nil
}

func (m *sessionManager) exists(id string) (bool, error) {
	m.mu.Lock()
	_, deleting := m.deleting[id]
	m.mu.Unlock()
	if deleting {
		return false, nil
	}
	meta, _, err := m.store.load(id)
	if err == nil {
		if _, workspaceErr := m.workspaces.load(meta.WorkspaceID); workspaceErr != nil {
			if errors.Is(workspaceErr, errWorkspaceNotFound) {
				return false, nil
			}
			return false, workspaceErr
		}
		return true, nil
	}
	if errors.Is(err, errSessionNotFound) {
		return false, nil
	}
	return false, err
}

func (m *sessionManager) list() ([]managedSession, error) {
	metas, err := m.store.list()
	if err != nil {
		return nil, err
	}
	m.mu.Lock()
	result := make([]managedSession, 0, len(metas))
	for _, meta := range metas {
		if _, deleting := m.deleting[meta.ID]; deleting {
			continue
		}
		current := m.sessions[meta.ID]
		running := current != nil && !current.isDone() && !current.stopping.Load()
		result = append(result, managedSession{
			Metadata:   meta,
			Running:    running,
			Outputting: running && current.isOutputting(),
		})
	}
	m.mu.Unlock()
	sortManagedSessions(result)
	return result, nil
}

func (m *sessionManager) listWorkspace(workspaceID string) ([]managedSession, error) {
	if _, err := m.workspaces.load(workspaceID); err != nil {
		return nil, err
	}
	all, err := m.list()
	if err != nil {
		return nil, err
	}
	result := make([]managedSession, 0, len(all))
	for _, session := range all {
		if session.Metadata.WorkspaceID == workspaceID {
			result = append(result, session)
		}
	}
	return result, nil
}

func sortManagedSessions(sessions []managedSession) {
	sort.SliceStable(sessions, func(i, j int) bool {
		if sessions[i].Running != sessions[j].Running {
			return sessions[i].Running
		}
		if sessions[i].Outputting != sessions[j].Outputting {
			return sessions[i].Outputting
		}
		left := sessions[i].Metadata
		right := sessions[j].Metadata
		if left.UpdatedAt.Equal(right.UpdatedAt) {
			return left.ID < right.ID
		}
		return left.UpdatedAt.After(right.UpdatedAt)
	})
}

func (m *sessionManager) getInWorkspace(workspaceID, id string) (managedSession, error) {
	if _, err := m.workspaces.load(workspaceID); err != nil {
		return managedSession{}, err
	}
	session, err := m.get(id)
	if err != nil {
		return managedSession{}, err
	}
	if session.Metadata.WorkspaceID != workspaceID {
		return managedSession{}, errSessionNotFound
	}
	return session, nil
}

func (m *sessionManager) get(id string) (managedSession, error) {
	meta, _, err := m.store.load(id)
	if err != nil {
		return managedSession{}, err
	}
	m.mu.Lock()
	deleting := false
	if _, deleting = m.deleting[id]; !deleting {
		current := m.sessions[id]
		running := current != nil && !current.isDone() && !current.stopping.Load()
		outputting := running && current.isOutputting()
		m.mu.Unlock()
		return managedSession{
			Metadata:   meta,
			Running:    running,
			Outputting: outputting,
		}, nil
	}
	m.mu.Unlock()
	return managedSession{}, errSessionNotFound
}

func (m *sessionManager) metrics(id string) (sessionMetricsResponse, error) {
	m.mu.Lock()
	_, deleting := m.deleting[id]
	m.mu.Unlock()
	if deleting {
		return sessionMetricsResponse{}, errSessionNotFound
	}
	return m.store.metrics(id)
}

func (m *sessionManager) rename(id, name string, forward bool) (managedSession, error) {
	m.mu.Lock()
	if _, deleting := m.deleting[id]; deleting {
		m.mu.Unlock()
		return managedSession{}, errSessionNotFound
	}
	m.mu.Unlock()

	meta, err := m.store.rename(id, name)
	if err != nil {
		return managedSession{}, err
	}
	m.mu.Lock()
	current := m.sessions[id]
	running := current != nil && !current.isDone() && !current.stopping.Load()
	outputting := running && current.isOutputting()
	m.mu.Unlock()
	if forward && running {
		command, _ := json.Marshal(map[string]string{"type": "set_session_name", "name": name})
		if err := current.submit(current.done, command); err != nil {
			m.cfg.Logger.Warn("forward session name to pi", "session_id", id, "error", err)
		}
	}
	return managedSession{Metadata: meta, Running: running, Outputting: outputting}, nil
}

func (m *sessionManager) touch(id string) error {
	return m.store.touch(id)
}

func (m *sessionManager) delete(ctx context.Context, id string) error {
	if _, _, err := m.store.load(id); err != nil {
		return err
	}
	m.mu.Lock()
	if _, deleting := m.deleting[id]; deleting {
		m.mu.Unlock()
		return errSessionDeleting
	}
	m.deleting[id] = struct{}{}
	current := m.sessions[id]
	m.mu.Unlock()

	deleted := false
	defer func() {
		if deleted {
			return
		}
		m.mu.Lock()
		delete(m.deleting, id)
		m.mu.Unlock()
	}()

	if current != nil && !current.isDone() {
		current.stop(1000, "session deleted")
		if err := waitForSessionStop(ctx, current); err != nil {
			return err
		}
	}
	if err := m.store.delete(id); err != nil {
		return err
	}
	m.mu.Lock()
	delete(m.sessions, id)
	delete(m.deleting, id)
	m.mu.Unlock()
	deleted = true
	return nil
}

// stop ends only the live pi process. Persisted session metadata, history, and
// replay data remain available, and a later attach starts a fresh pi process.
func (m *sessionManager) stop(ctx context.Context, id string) error {
	if _, _, err := m.store.load(id); err != nil {
		return err
	}

	m.mu.Lock()
	if _, deleting := m.deleting[id]; deleting {
		m.mu.Unlock()
		return errSessionDeleting
	}
	current := m.sessions[id]
	m.mu.Unlock()

	if current == nil || current.isDone() {
		return nil
	}
	if !current.stopping.Load() && !current.stopIfSettled(1000, "pi process stopped by user") {
		return errSessionOutputting
	}
	return waitForSessionStop(ctx, current)
}

func waitForSessionStop(ctx context.Context, session *piSession) error {
	select {
	case <-session.done:
		return nil
	case <-ctx.Done():
		session.forceKill()
		timer := time.NewTimer(2 * time.Second)
		defer timer.Stop()
		select {
		case <-session.done:
			return nil
		case <-timer.C:
			return ctx.Err()
		}
	}
}

func (m *sessionManager) getOrStart(
	id,
	dir,
	workspaceID,
	workDir string,
	args []string,
	newSession bool,
) (*piSession, bool, error) {
	for {
		m.mu.Lock()
		if m.closing {
			m.mu.Unlock()
			return nil, false, errors.New("session manager is shutting down")
		}
		if _, deleting := m.deleting[id]; deleting {
			m.mu.Unlock()
			return nil, false, errSessionDeleting
		}
		if current := m.sessions[id]; current != nil && !current.isDone() {
			if !current.stopping.Load() {
				m.mu.Unlock()
				return current, false, nil
			}
			done := current.done
			m.mu.Unlock()
			timer := time.NewTimer(m.cfg.HistoryTimeout)
			select {
			case <-done:
				if !timer.Stop() {
					<-timer.C
				}
				continue
			case <-timer.C:
				return nil, false, errors.New("previous pi session is still stopping")
			}
		}

		session := newPiSession(piSessionConfig{
			ID:             id,
			Dir:            dir,
			WorkspaceID:    workspaceID,
			Command:        m.cfg.PiCommand,
			Args:           args,
			Environment:    m.cfg.childEnvironment(),
			WorkDir:        workDir,
			MaxEventBytes:  m.cfg.MaxMessageBytes,
			InputQueueSize: m.cfg.InputQueueSize,
			SessionIdle:    m.cfg.SessionIdle,
			NewSession:     newSession,
			Logger:         m.cfg.Logger,
			OnExit:         m.sessionExited,
			OnActivity: func() error {
				return m.store.touch(id)
			},
			OnName: func(name string) (bool, error) {
				return m.store.adoptName(id, name)
			},
			OnMetric: func(sample sessionMetricSample) error {
				return m.store.appendMetric(id, sample)
			},
		})
		if err := session.openReplayStore(newSession); err != nil {
			m.mu.Unlock()
			return nil, false, fmt.Errorf("open session replay store: %w", err)
		}
		m.sessions[id] = session
		m.wg.Add(1)
		if err := session.start(); err != nil {
			session.closeReplayStore()
			delete(m.sessions, id)
			m.wg.Done()
			m.mu.Unlock()
			return nil, false, err
		}
		m.mu.Unlock()
		return session, true, nil
	}
}

func (m *sessionManager) argsForWorkspace(workspace workspaceMetadata) []string {
	return piArgsForWorkspace(m.cfg.PiArgs, workspace)
}

func piArgsForWorkspace(base []string, workspace workspaceMetadata) []string {
	additional := len(workspace.SkillPaths)*2 + len(workspace.ExtensionPaths)*2 + 4
	args := make([]string, 0, len(base)+additional)
	args = append(args, base...)
	if workspace.NoSkills {
		args = append(args, "--no-skills")
	}
	for _, path := range workspace.SkillPaths {
		args = append(args, "--skill", path)
	}
	if workspace.NoExtensions {
		args = append(args, "--no-extensions")
	}
	for _, path := range workspace.ExtensionPaths {
		args = append(args, "--extension", path)
	}
	if workspace.AdditionalSystemPrompt != "" {
		args = append(args, "--append-system-prompt", workspace.AdditionalSystemPrompt)
	}
	return args
}

func (m *sessionManager) sessionExited(session *piSession, _ error) {
	m.mu.Lock()
	if m.sessions[session.id] == session {
		delete(m.sessions, session.id)
	}
	m.mu.Unlock()
	m.wg.Done()
}

// recycleSettled restarts idle pi runtimes on their clients' normal reconnect
// path so process-local model registries observe a provider credential change.
// Active model calls are intentionally left alone and finish with the runtime
// that started them.
func (m *sessionManager) recycleSettled(reason string) {
	m.mu.Lock()
	sessions := make([]*piSession, 0, len(m.sessions))
	for _, session := range m.sessions {
		if !session.isDone() && !session.stopping.Load() {
			sessions = append(sessions, session)
		}
	}
	m.mu.Unlock()
	for _, session := range sessions {
		if !session.isOutputting() {
			session.stop(1001, reason)
		}
	}
}

func (m *sessionManager) shutdown(ctx context.Context) error {
	m.mu.Lock()
	if !m.closing {
		m.closing = true
	}
	sessions := make([]*piSession, 0, len(m.sessions))
	for _, session := range m.sessions {
		sessions = append(sessions, session)
	}
	m.mu.Unlock()

	for _, session := range sessions {
		session.requestStop()
	}

	waited := make(chan struct{})
	go func() {
		m.wg.Wait()
		close(waited)
	}()

	select {
	case <-waited:
		return nil
	case <-ctx.Done():
		for _, session := range sessions {
			session.forceKill()
		}
		timer := time.NewTimer(2 * time.Second)
		defer timer.Stop()
		select {
		case <-waited:
		case <-timer.C:
		}
		return ctx.Err()
	}
}
