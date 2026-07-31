package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/yearsyan/pi2ws/internal/gateway"
)

type stringList []string

func (values *stringList) String() string {
	return fmt.Sprint([]string(*values))
}

func (values *stringList) Set(value string) error {
	*values = append(*values, value)
	return nil
}

func main() {
	os.Exit(run())
}

func run() int {
	var piArgs stringList
	var allowedOrigins stringList

	listen := flag.String("listen", envOr("PI2WS_LISTEN", "127.0.0.1:8080"), "HTTP listen address")
	token := flag.String("token", os.Getenv("PI2WS_TOKEN"), "URL authentication token (or PI2WS_TOKEN)")
	dataDir := flag.String("data-dir", envOr("PI2WS_DATA_DIR", defaultDataDir()), "persistent data directory")
	workDir := flag.String("work-dir", envOr("PI2WS_WORK_DIR", mustWorkingDir()), "working directory for pi processes")
	piCommand := flag.String("pi", envOr("PI2WS_PI_COMMAND", "pi"), "pi executable")
	maxMessage := flag.Int64("max-message-bytes", 16<<20, "maximum WebSocket command and pi event size")
	shutdownTimeout := flag.Duration("shutdown-timeout", 10*time.Second, "graceful shutdown timeout")
	flag.Var(&piArgs, "pi-arg", "extra pi argument; repeat for multiple arguments")
	flag.Var(&allowedOrigins, "allow-origin", `allowed WebSocket Origin; repeat or use "*"`)
	flag.Parse()

	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{}))
	if *shutdownTimeout <= 0 {
		logger.Error("shutdown timeout must be positive")
		return 2
	}

	app, err := gateway.New(gateway.Config{
		Token:           *token,
		DataDir:         *dataDir,
		WorkDir:         *workDir,
		PiCommand:       *piCommand,
		PiArgs:          piArgs,
		AllowedOrigins:  allowedOrigins,
		MaxMessageBytes: *maxMessage,
		Logger:          logger,
	})
	if err != nil {
		logger.Error("configure pi2ws", "error", err)
		return 2
	}

	server := &http.Server{
		Addr:              *listen,
		Handler:           app.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	signalContext, stopSignals := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stopSignals()

	serveResult := make(chan error, 1)
	go func() {
		logger.Info("pi2ws listening", "address", *listen)
		serveResult <- server.ListenAndServe()
	}()

	exitCode := 0
	select {
	case <-signalContext.Done():
		logger.Info("shutdown requested")
	case err := <-serveResult:
		if !errors.Is(err, http.ErrServerClosed) {
			logger.Error("HTTP server stopped", "error", err)
			exitCode = 1
		}
	}

	processContext, cancelProcesses := context.WithTimeout(context.Background(), *shutdownTimeout)
	if err := app.Shutdown(processContext); err != nil {
		logger.Error("stop pi processes", "error", err)
		exitCode = 1
	}
	cancelProcesses()

	httpContext, cancelHTTP := context.WithTimeout(context.Background(), *shutdownTimeout)
	if err := server.Shutdown(httpContext); err != nil {
		logger.Error("stop HTTP server", "error", err)
		exitCode = 1
	}
	cancelHTTP()
	return exitCode
}

func envOr(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func mustWorkingDir() string {
	dir, err := os.Getwd()
	if err != nil {
		return "."
	}
	return dir
}

func defaultDataDir() string {
	if stateHome := os.Getenv("XDG_STATE_HOME"); stateHome != "" {
		return filepath.Join(stateHome, "pi2ws")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join(".", ".pi2ws")
	}
	return filepath.Join(home, ".local", "state", "pi2ws")
}
