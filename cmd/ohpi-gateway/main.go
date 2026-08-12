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

var version = "dev"

const restartExitCode = 75

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
	tokenFile := flag.String("token-file", os.Getenv("OHPI_TOKEN_FILE"), "read authentication token from this file (or OHPI_TOKEN_FILE)")
	showVersion := flag.Bool("version", false, "print version and exit")
	dataDir := flag.String("data-dir", defaultDataDir(), "persistent data directory")
	workDir := flag.String("work-dir", mustWorkingDir(), "working directory for pi processes")
	titleModel := flag.String("title-model", "auto", "session title model: auto, active, off, or provider/model-id")
	piCommand := flag.String("pi", envOr("OHPI_PI_COMMAND", "pi"), "pi executable")
	piEnvironmentPath := flag.String("pi-env-path", os.Getenv("OHPI_PI_ENV_PATH"), "PATH supplied to pi child processes")
	piEnvironmentFile := flag.String("pi-env-file", os.Getenv("OHPI_PI_ENV_FILE"), "shell file sourced for pi child process environment")
	piEnvironmentShell := flag.String("pi-env-shell", os.Getenv("OHPI_PI_ENV_SHELL"), "shell used to source --pi-env-file")
	maxMessage := flag.Int64("max-message-bytes", 128<<20, "maximum WebSocket command and pi event size")
	sessionIdle := flag.Duration("session-idle-timeout", 5*time.Minute, "stop a settled pi session after this idle period")
	scheduledSessionRetention := flag.Duration(
		"scheduled-session-retention",
		7*24*time.Hour,
		"delete inactive scheduled-task sessions after this period",
	)
	shutdownTimeout := flag.Duration("shutdown-timeout", 10*time.Second, "graceful shutdown timeout")
	flag.Var(&piArgs, "pi-arg", "extra pi argument; repeat for multiple arguments")
	flag.Var(&allowedOrigins, "allow-origin", `allowed WebSocket Origin; repeat or use "*"`)
	flag.Parse()
	if *showVersion {
		fmt.Printf("ohpi-gateway %s\n", version)
		return 0
	}

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
	*piCommand = resolveRuntimeConfigValue(*piCommand, explicitFlags["pi"], "OHPI_PI_COMMAND", fileConfig)
	*piEnvironmentPath = resolveRuntimeConfigValue(*piEnvironmentPath, explicitFlags["pi-env-path"], "OHPI_PI_ENV_PATH", fileConfig)
	*piEnvironmentFile = resolveRuntimeConfigValue(*piEnvironmentFile, explicitFlags["pi-env-file"], "OHPI_PI_ENV_FILE", fileConfig)
	*piEnvironmentShell = resolveRuntimeConfigValue(*piEnvironmentShell, explicitFlags["pi-env-shell"], "OHPI_PI_ENV_SHELL", fileConfig)
	scheduledRetentionValue := resolveRuntimeConfigValue(
		scheduledSessionRetention.String(),
		explicitFlags["scheduled-session-retention"],
		"OHPI_SCHEDULED_SESSION_RETENTION",
		fileConfig,
	)
	*scheduledSessionRetention, err = time.ParseDuration(scheduledRetentionValue)
	if err != nil || *scheduledSessionRetention < time.Hour {
		logger.Error("scheduled session retention must be a duration of at least one hour", "value", scheduledRetentionValue)
		return 2
	}
	*titleModel, err = normalizeTitleModel(*titleModel)
	if err != nil {
		logger.Error("configure title model", "error", err)
		return 2
	}
	*token, err = resolveAuthenticationToken(*token, *tokenFile)
	if err != nil {
		logger.Error("load authentication token", "error", err)
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

	restartRequest := make(chan struct{}, 1)
	app, err := gateway.New(gateway.Config{
		Token:              *token,
		Version:            version,
		DataDir:            *dataDir,
		WorkDir:            *workDir,
		PiCommand:          *piCommand,
		PiEnvironmentPath:  *piEnvironmentPath,
		PiEnvironmentFile:  *piEnvironmentFile,
		PiEnvironmentShell: *piEnvironmentShell,
		PiArgs:             piArgs,
		RuntimeConfigPath:  *configFile,
		TitleModel:         *titleModel,
		RequestRestart: func() {
			select {
			case restartRequest <- struct{}{}:
			default:
			}
		},
		AllowedOrigins:            allowedOrigins,
		MaxMessageBytes:           *maxMessage,
		SessionIdle:               *sessionIdle,
		ScheduledSessionRetention: *scheduledSessionRetention,
		Logger:                    logger,
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
	restartRequested := false
	select {
	case <-signalContext.Done():
		logger.Info("shutdown requested")
	case <-restartRequest:
		restartRequested = true
		logger.Info("restart requested")
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
	if restartRequested && exitCode == 0 {
		exitCode = restartExitCode
	}
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
