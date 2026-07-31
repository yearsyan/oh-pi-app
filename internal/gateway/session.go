package gateway

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/gorilla/websocket"
)

type piSession struct {
	id         string
	dir        string
	command    string
	args       []string
	workDir    string
	maxEvent   int64
	logger     *slog.Logger
	onExit     func(*piSession, error)
	input      chan []byte
	processEnd chan struct{}
	done       chan struct{}

	cmd      *exec.Cmd
	stdin    io.WriteCloser
	ioWG     sync.WaitGroup
	stopOnce sync.Once
	stopping atomic.Bool

	failureMu sync.Mutex
	failure   error

	clientsMu     sync.Mutex
	clientsClosed bool
	clients       map[*wsClient]struct{}
}

type piSessionConfig struct {
	ID             string
	Dir            string
	Command        string
	Args           []string
	WorkDir        string
	MaxEventBytes  int64
	InputQueueSize int
	Logger         *slog.Logger
	OnExit         func(*piSession, error)
}

func newPiSession(cfg piSessionConfig) *piSession {
	return &piSession{
		id:         cfg.ID,
		dir:        cfg.Dir,
		command:    cfg.Command,
		args:       append([]string(nil), cfg.Args...),
		workDir:    cfg.WorkDir,
		maxEvent:   cfg.MaxEventBytes,
		logger:     cfg.Logger.With("session_id", cfg.ID),
		onExit:     cfg.OnExit,
		input:      make(chan []byte, cfg.InputQueueSize),
		processEnd: make(chan struct{}),
		done:       make(chan struct{}),
		clients:    make(map[*wsClient]struct{}),
	}
}

func (s *piSession) start() error {
	args := append([]string(nil), s.args...)
	args = append(args,
		"--mode", "rpc",
		"--session-dir", s.dir,
		"--session-id", s.id,
	)

	s.cmd = exec.Command(s.command, args...)
	s.cmd.Dir = s.workDir
	s.cmd.Env = childEnvironment()

	stdin, err := s.cmd.StdinPipe()
	if err != nil {
		return fmt.Errorf("open pi stdin: %w", err)
	}
	stdout, err := s.cmd.StdoutPipe()
	if err != nil {
		_ = stdin.Close()
		return fmt.Errorf("open pi stdout: %w", err)
	}
	stderr, err := s.cmd.StderrPipe()
	if err != nil {
		_ = stdin.Close()
		_ = stdout.Close()
		return fmt.Errorf("open pi stderr: %w", err)
	}
	if err := s.cmd.Start(); err != nil {
		_ = stdin.Close()
		_ = stdout.Close()
		_ = stderr.Close()
		return fmt.Errorf("start pi: %w", err)
	}
	s.stdin = stdin

	s.logger.Info("pi process started", "pid", s.cmd.Process.Pid)

	s.ioWG.Add(3)
	go s.writeInput()
	go s.readOutput(stdout)
	go s.readStderr(stderr)
	go s.wait()
	return nil
}

func (s *piSession) submit(clientDone <-chan struct{}, command []byte) error {
	select {
	case <-s.done:
		return errors.New("pi session is not running")
	default:
	}

	select {
	case s.input <- command:
		return nil
	case <-s.done:
		return errors.New("pi session is not running")
	case <-clientDone:
		return errors.New("WebSocket client disconnected")
	}
}

func (s *piSession) addClient(client *wsClient) bool {
	s.clientsMu.Lock()
	defer s.clientsMu.Unlock()
	if s.clientsClosed {
		return false
	}
	s.clients[client] = struct{}{}
	return true
}

func (s *piSession) removeClient(client *wsClient) {
	s.clientsMu.Lock()
	delete(s.clients, client)
	s.clientsMu.Unlock()
}

func (s *piSession) broadcast(message []byte) {
	s.clientsMu.Lock()
	clients := make([]*wsClient, 0, len(s.clients))
	for client := range s.clients {
		clients = append(clients, client)
	}
	s.clientsMu.Unlock()

	for _, client := range clients {
		if !client.enqueue(message) {
			s.removeClient(client)
		}
	}
}

func (s *piSession) requestStop() {
	s.stopOnce.Do(func() {
		s.stopping.Store(true)
		s.closeClients(websocket.CloseGoingAway, "pi2ws is shutting down")
		if s.stdin != nil {
			_ = s.stdin.Close()
		}
	})
}

func (s *piSession) forceKill() {
	if s.cmd != nil && s.cmd.Process != nil {
		_ = s.cmd.Process.Kill()
	}
}

func (s *piSession) isDone() bool {
	select {
	case <-s.done:
		return true
	default:
		return false
	}
}

func (s *piSession) writeInput() {
	defer s.ioWG.Done()
	for {
		select {
		case <-s.processEnd:
			return
		default:
		}

		select {
		case command := <-s.input:
			if _, err := s.stdin.Write(command); err != nil {
				s.fail(fmt.Errorf("write pi stdin: %w", err))
				return
			}
			if _, err := s.stdin.Write([]byte{'\n'}); err != nil {
				s.fail(fmt.Errorf("write pi stdin delimiter: %w", err))
				return
			}
		case <-s.processEnd:
			return
		}
	}
}

func (s *piSession) readOutput(stdout io.Reader) {
	defer s.ioWG.Done()
	scanner := bufio.NewScanner(stdout)
	scanner.Split(splitLF)
	scanner.Buffer(make([]byte, 64<<10), int(s.maxEvent))
	for scanner.Scan() {
		message := bytes.Clone(scanner.Bytes())
		s.broadcast(message)
	}
	if err := scanner.Err(); err != nil {
		s.fail(fmt.Errorf("read pi stdout: %w", err))
	}
}

func (s *piSession) readStderr(stderr io.Reader) {
	defer s.ioWG.Done()
	scanner := bufio.NewScanner(stderr)
	scanner.Buffer(make([]byte, 16<<10), 1<<20)
	for scanner.Scan() {
		s.logger.Warn("pi stderr", "message", scanner.Text())
	}
	if err := scanner.Err(); err != nil {
		s.logger.Warn("read pi stderr", "error", err)
	}
}

func (s *piSession) wait() {
	waitErr := s.cmd.Wait()
	close(s.processEnd)
	s.ioWG.Wait()

	s.failureMu.Lock()
	failure := s.failure
	s.failureMu.Unlock()
	if failure == nil && waitErr != nil {
		failure = waitErr
	}

	close(s.done)
	if s.stopping.Load() {
		s.closeClients(websocket.CloseGoingAway, "pi2ws is shutting down")
		s.logger.Info("pi process stopped")
	} else {
		reason := "pi process exited"
		if failure != nil {
			reason = "pi process failed"
			s.logger.Error(reason, "error", failure)
		} else {
			s.logger.Warn(reason)
		}
		s.closeClients(websocket.CloseInternalServerErr, reason)
	}
	s.onExit(s, failure)
}

func (s *piSession) fail(err error) {
	if s.stopping.Load() {
		return
	}
	s.failureMu.Lock()
	if s.failure == nil {
		s.failure = err
	}
	s.failureMu.Unlock()
	s.forceKill()
}

func (s *piSession) closeClients(code int, reason string) {
	s.clientsMu.Lock()
	if s.clientsClosed {
		s.clientsMu.Unlock()
		return
	}
	s.clientsClosed = true
	clients := make([]*wsClient, 0, len(s.clients))
	for client := range s.clients {
		clients = append(clients, client)
	}
	clear(s.clients)
	s.clientsMu.Unlock()

	for _, client := range clients {
		client.close(code, reason)
	}
}

func splitLF(data []byte, atEOF bool) (advance int, token []byte, err error) {
	if index := bytes.IndexByte(data, '\n'); index >= 0 {
		record := data[:index]
		if len(record) > 0 && record[len(record)-1] == '\r' {
			record = record[:len(record)-1]
		}
		return index + 1, record, nil
	}
	if atEOF && len(data) > 0 {
		return len(data), data, nil
	}
	return 0, nil, nil
}

func childEnvironment() []string {
	const secretName = "PI2WS_TOKEN="
	environment := make([]string, 0, len(os.Environ()))
	for _, entry := range os.Environ() {
		if strings.HasPrefix(entry, secretName) {
			continue
		}
		environment = append(environment, entry)
	}
	return environment
}
