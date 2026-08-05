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

	"github.com/yearsyan/oh-pi-app/internal/gateway"
	"github.com/yearsyan/oh-pi-app/internal/titleextension"
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

	configFile := flag.String("config", envOr("OHPI_CONFIG_FILE", defaultConfigFile()), "configuration file (or OHPI_CONFIG_FILE)")
	listen := flag.String("listen", "127.0.0.1:18080", "HTTP listen address")
	token := flag.String("token", os.Getenv("OHPI_TOKEN"), "URL authentication token (or OHPI_TOKEN)")
	dataDir := flag.String("data-dir", defaultDataDir(), "persistent data directory")
	workDir := flag.String("work-dir", mustWorkingDir(), "working directory for pi processes")
	titleModel := flag.String("title-model", "auto", "session title model: auto, active, off, or provider/model-id")
	piCommand := flag.String("pi", envOr("OHPI_PI_COMMAND", "pi"), "pi executable")
	maxMessage := flag.Int64("max-message-bytes", 128<<20, "maximum WebSocket command and pi event size")
	sessionIdle := flag.Duration("session-idle-timeout", 5*time.Minute, "stop a settled pi session after this idle period")
	shutdownTimeout := flag.Duration("shutdown-timeout", 10*time.Second, "graceful shutdown timeout")
	flag.Var(&piArgs, "pi-arg", "extra pi argument; repeat for multiple arguments")
	flag.Var(&allowedOrigins, "allow-origin", `allowed WebSocket Origin; repeat or use "*"`)
	flag.Parse()

	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{}))
	explicitFlags := make(map[string]bool)
	flag.Visit(func(value *flag.Flag) {
		explicitFlags[value.Name] = true
	})
	configRequired := explicitFlags["config"] || os.Getenv("OHPI_CONFIG_FILE") != ""
	fileConfig, err := loadRuntimeConfig(*configFile, configRequired)
	if err != nil {
		logger.Error("load configuration file", "path", *configFile, "error", err)
		return 2
	}
	*listen = resolveRuntimeConfigValue(*listen, explicitFlags["listen"], "OHPI_LISTEN", fileConfig)
	*dataDir = resolveRuntimeConfigValue(*dataDir, explicitFlags["data-dir"], "OHPI_DATA_DIR", fileConfig)
	*workDir = resolveRuntimeConfigValue(*workDir, explicitFlags["work-dir"], "OHPI_WORK_DIR", fileConfig)
	*titleModel = resolveRuntimeConfigValue(*titleModel, explicitFlags["title-model"], "OHPI_TITLE_MODEL", fileConfig)
	*titleModel, err = normalizeTitleModel(*titleModel)
	if err != nil {
		logger.Error("configure title model", "error", err)
		return 2
	}

	if *shutdownTimeout <= 0 {
		logger.Error("shutdown timeout must be positive")
		return 2
	}
	if *sessionIdle <= 0 {
		logger.Error("session idle timeout must be positive")
		return 2
	}
	if *titleModel != "off" {
		extensionPath, err := titleextension.Install(*dataDir)
		if err != nil {
			logger.Error("install session title extension", "error", err)
			return 2
		}
		piArgs = append(piArgs,
			"--extension", extensionPath,
			"--ohpi-title-model", *titleModel,
		)
	}

	app, err := gateway.New(gateway.Config{
		Token:           *token,
		DataDir:         *dataDir,
		WorkDir:         *workDir,
		PiCommand:       *piCommand,
		PiArgs:          piArgs,
		AllowedOrigins:  allowedOrigins,
		MaxMessageBytes: *maxMessage,
		SessionIdle:     *sessionIdle,
		Logger:          logger,
	})
	if err != nil {
		logger.Error("configure ohpi-gateway", "error", err)
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
		logger.Info("ohpi-gateway listening", "address", *listen)
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
		return filepath.Join(stateHome, appDirectoryName)
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return filepath.Join(".", "."+appDirectoryName)
	}
	return filepath.Join(home, ".local", "state", appDirectoryName)
}
