package gateway

import (
	"context"
	"errors"
	"fmt"
	"time"
	"unicode/utf8"

	"github.com/yearsyan/oh-pi-app/internal/scheduledtask"
)

const scheduledRunTimeout = time.Hour

func (g *Gateway) runScheduledTask(
	ctx context.Context,
	task scheduledtask.Task,
	run scheduledtask.Run,
	reporter scheduledtask.Reporter,
) error {
	if _, err := g.workspaces.load(task.WorkspaceID); err != nil {
		if errors.Is(err, errWorkspaceNotFound) {
			return errors.New("scheduled task workspace is unavailable")
		}
		return fmt.Errorf("load scheduled task workspace: %w", err)
	}
	initial, err := scheduledTaskInitialSessionConfig(task)
	if err != nil {
		return fmt.Errorf("validate scheduled task model: %w", err)
	}
	runContext, cancel := context.WithTimeout(ctx, scheduledRunTimeout)
	defer cancel()

	session, err := g.manager.createScheduled(task.WorkspaceID, task.ID, initial)
	if err != nil {
		return err
	}
	reporter.SetSessionID(session.id)
	// A scheduled occurrence is one unattended turn. Its durable session stays
	// attachable, but keeping the idle pi process alive would waste resources.
	defer session.stop(1000, "scheduled task finished")

	name := scheduledSessionName(task.Name)
	if _, err := g.manager.rename(session.id, name, false); err != nil {
		return fmt.Errorf("name scheduled task session: %w", err)
	}
	if err := session.setSessionName(runContext, name); err != nil {
		return fmt.Errorf("set scheduled task session name: %w", err)
	}

	sourceID := "ohpi-scheduled:" + run.ID
	turnDone, err := session.beginObservedTurn(sourceID)
	if err != nil {
		return err
	}
	command, err := scheduledPromptCommand(sourceID, scheduledTaskPrompt(task.Prompt, run.EventData))
	if err != nil {
		session.finishObservedTurn(err)
		return err
	}
	if err := session.submit(session.done, command); err != nil {
		session.finishObservedTurn(err)
		return err
	}

	select {
	case turnErr := <-turnDone:
		if errors.Is(turnErr, errScheduledTurnNeedsInteraction) {
			_ = session.submit(session.done, []byte(`{"type":"abort"}`))
		}
		return turnErr
	case <-runContext.Done():
		session.finishObservedTurn(runContext.Err())
		_ = session.submit(session.done, []byte(`{"type":"abort"}`))
		return runContext.Err()
	}
}

func scheduledTaskPrompt(prompt, eventData string) string {
	if eventData == "" {
		return prompt
	}
	return prompt + "\n\n" +
		"<system-reminder>本次任务由外部触发器触发，是非交互式任务，不要执行需要用户交互的操作，" +
		"本次触发器数据为 " + eventData + " </system-reminder>"
}

func scheduledTaskInitialSessionConfig(task scheduledtask.Task) (initialSessionConfig, error) {
	initial, err := parseInitialSessionConfig(task.Model, task.Thinking)
	if err != nil {
		return initialSessionConfig{}, err
	}
	initial.skillPaths = append([]string(nil), task.SkillPaths...)
	initial.noSkills = task.NoSkills
	return initial, nil
}

func scheduledSessionName(taskName string) string {
	const prefix = "[定时] "
	const maxRunes = 200
	name := prefix + taskName
	if utf8.RuneCountInString(name) <= maxRunes {
		return name
	}
	characters := []rune(name)
	return string(characters[:maxRunes])
}
