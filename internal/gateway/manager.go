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

var errSessionDeleting = errors.New("session is being deleted")

type managedSession struct {
	Metadata sessionMetadata
	Running  bool
}

type sessionManager struct {
	cfg      Config
	store    *sessionStore
	mu       sync.Mutex
	sessions map[string]*piSession
	deleting map[string]struct{}
	closing  bool
	wg       sync.WaitGroup
}

func newSessionManager(cfg Config, store *sessionStore) *sessionManager {
	return &sessionManager{
		cfg:      cfg,
		store:    store,
		sessions: make(map[string]*piSession),
		deleting: make(map[string]struct{}),
	}
}

func (m *sessionManager) create(workDir string, initial initialSessionConfig) (*piSession, error) {
	meta, dir, err := m.store.create(workDir)
	if err != nil {
		return nil, err
	}
	args := append([]string(nil), m.cfg.PiArgs...)
	args = append(args, initial.args()...)
	session, _, err := m.getOrStart(meta.ID, dir, meta.WorkDir, args, true)
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

func (m *sessionManager) attach(id string) (*piSession, error) {
	meta, dir, err := m.store.load(id)
	if err != nil {
		return nil, err
	}
	workDir := meta.WorkDir
	if workDir == "" {
		// Sessions persisted before workspaces existed fall back to the
		// gateway-wide working directory.
		workDir = m.cfg.WorkDir
	}
	session, started, err := m.getOrStart(meta.ID, dir, workDir, m.cfg.PiArgs, false)
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
	if started && meta.NameSet {
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
	_, _, err := m.store.load(id)
	if err == nil {
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
		result = append(result, managedSession{
			Metadata: meta,
			Running:  current != nil && !current.isDone() && !current.stopping.Load(),
		})
	}
	m.mu.Unlock()
	sort.Slice(result, func(i, j int) bool {
		left := result[i].Metadata
		right := result[j].Metadata
		if left.UpdatedAt.Equal(right.UpdatedAt) {
			return left.ID < right.ID
		}
		return left.UpdatedAt.After(right.UpdatedAt)
	})
	return result, nil
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
		m.mu.Unlock()
		return managedSession{
			Metadata: meta,
			Running:  current != nil && !current.isDone() && !current.stopping.Load(),
		}, nil
	}
	m.mu.Unlock()
	return managedSession{}, errSessionNotFound
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
	m.mu.Unlock()
	if forward && running {
		command, _ := json.Marshal(map[string]string{"type": "set_session_name", "name": name})
		if err := current.submit(current.done, command); err != nil {
			m.cfg.Logger.Warn("forward session name to pi", "session_id", id, "error", err)
		}
	}
	return managedSession{Metadata: meta, Running: running}, nil
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
		select {
		case <-current.done:
		case <-ctx.Done():
			current.forceKill()
			timer := time.NewTimer(2 * time.Second)
			select {
			case <-current.done:
				if !timer.Stop() {
					<-timer.C
				}
			case <-timer.C:
				return ctx.Err()
			}
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

func (m *sessionManager) getOrStart(id, dir, workDir string, args []string, newSession bool) (*piSession, bool, error) {
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
			Command:        m.cfg.PiCommand,
			Args:           args,
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
			OnName: func(name string) error {
				return m.store.adoptName(id, name)
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

func (m *sessionManager) sessionExited(session *piSession, _ error) {
	m.mu.Lock()
	if m.sessions[session.id] == session {
		delete(m.sessions, session.id)
	}
	m.mu.Unlock()
	m.wg.Done()
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
