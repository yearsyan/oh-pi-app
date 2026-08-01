package gateway

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"
)

type sessionManager struct {
	cfg      Config
	store    *sessionStore
	mu       sync.Mutex
	sessions map[string]*piSession
	closing  bool
	wg       sync.WaitGroup
}

func newSessionManager(cfg Config, store *sessionStore) *sessionManager {
	return &sessionManager{
		cfg:      cfg,
		store:    store,
		sessions: make(map[string]*piSession),
	}
}

func (m *sessionManager) create(workDir string) (*piSession, error) {
	meta, dir, err := m.store.create(workDir)
	if err != nil {
		return nil, err
	}
	session, err := m.getOrStart(meta.ID, dir, meta.WorkDir)
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
	session, err := m.getOrStart(meta.ID, dir, workDir)
	if err != nil {
		return nil, fmt.Errorf("start existing session: %w", err)
	}
	return session, nil
}

func (m *sessionManager) exists(id string) (bool, error) {
	_, _, err := m.store.load(id)
	if err == nil {
		return true, nil
	}
	if errors.Is(err, errSessionNotFound) {
		return false, nil
	}
	return false, err
}

func (m *sessionManager) getOrStart(id, dir, workDir string) (*piSession, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closing {
		return nil, errors.New("session manager is shutting down")
	}
	if current := m.sessions[id]; current != nil && !current.isDone() {
		return current, nil
	}

	session := newPiSession(piSessionConfig{
		ID:             id,
		Dir:            dir,
		Command:        m.cfg.PiCommand,
		Args:           m.cfg.PiArgs,
		WorkDir:        workDir,
		MaxEventBytes:  m.cfg.MaxMessageBytes,
		InputQueueSize: m.cfg.InputQueueSize,
		Logger:         m.cfg.Logger,
		OnExit:         m.sessionExited,
	})
	m.sessions[id] = session
	m.wg.Add(1)
	if err := session.start(); err != nil {
		delete(m.sessions, id)
		m.wg.Done()
		return nil, err
	}
	return session, nil
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
