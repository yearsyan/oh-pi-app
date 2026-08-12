// Package scheduledtask provides durable, process-local scheduling for gateway
// tasks. It deliberately owns only task timing and persistence; callers supply
// the execution adapter that turns a due task into application work.
package scheduledtask

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/robfig/cron/v3"
)

// ScheduleKind identifies one supported timing model.
type ScheduleKind string

const (
	ScheduleCron     ScheduleKind = "cron"
	ScheduleInterval ScheduleKind = "interval"
	ScheduleOnce     ScheduleKind = "once"
	ScheduleHTTP     ScheduleKind = "http"
)

const minimumInterval = time.Minute

// Schedule is the persisted timing definition for a task. Cron expressions
// are the standard five fields (minute, hour, day of month, month, weekday).
type Schedule struct {
	Kind         ScheduleKind `json:"kind"`
	Expression   string       `json:"expression,omitempty"`
	TimeZone     string       `json:"timezone,omitempty"`
	EverySeconds int64        `json:"every_seconds,omitempty"`
	AnchorAt     *time.Time   `json:"anchor_at,omitempty"`
	At           *time.Time   `json:"at,omitempty"`
}

type parsedSchedule interface {
	Next(time.Time) time.Time
}

type intervalSchedule struct {
	every  time.Duration
	anchor time.Time
}

func (schedule intervalSchedule) Next(after time.Time) time.Time {
	if after.Before(schedule.anchor) {
		return schedule.anchor
	}
	elapsed := after.Sub(schedule.anchor)
	return schedule.anchor.Add((elapsed/schedule.every + 1) * schedule.every)
}

type onceSchedule struct {
	at time.Time
}

type httpSchedule struct{}

func (httpSchedule) Next(time.Time) time.Time {
	return time.Time{}
}

func (schedule onceSchedule) Next(after time.Time) time.Time {
	if schedule.at.After(after) {
		return schedule.at
	}
	return time.Time{}
}

func (schedule Schedule) parse() (parsedSchedule, error) {
	switch schedule.Kind {
	case ScheduleCron:
		expression := strings.TrimSpace(schedule.Expression)
		if len(strings.Fields(expression)) != 5 {
			return nil, errors.New("cron expression must contain exactly five fields")
		}
		zone := strings.TrimSpace(schedule.TimeZone)
		if zone == "" {
			return nil, errors.New("cron timezone is required")
		}
		if strings.ContainsAny(zone, " \t\r\n") {
			return nil, errors.New("cron timezone is invalid")
		}
		if _, err := time.LoadLocation(zone); err != nil {
			return nil, fmt.Errorf("load cron timezone %q: %w", zone, err)
		}
		parser := cron.NewParser(cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow)
		parsed, err := parser.Parse("CRON_TZ=" + zone + " " + expression)
		if err != nil {
			return nil, fmt.Errorf("parse cron expression: %w", err)
		}
		return parsed, nil

	case ScheduleInterval:
		if schedule.EverySeconds < int64(minimumInterval/time.Second) {
			return nil, fmt.Errorf("interval must be at least %s", minimumInterval)
		}
		if schedule.AnchorAt == nil || schedule.AnchorAt.IsZero() {
			return nil, errors.New("interval anchor_at is required")
		}
		every := time.Duration(schedule.EverySeconds) * time.Second
		if every/time.Second != time.Duration(schedule.EverySeconds) {
			return nil, errors.New("interval is too large")
		}
		return intervalSchedule{every: every, anchor: schedule.AnchorAt.UTC()}, nil

	case ScheduleOnce:
		if schedule.At == nil || schedule.At.IsZero() {
			return nil, errors.New("once at is required")
		}
		return onceSchedule{at: schedule.At.UTC()}, nil

	case ScheduleHTTP:
		return httpSchedule{}, nil

	default:
		return nil, fmt.Errorf("unsupported schedule kind %q", schedule.Kind)
	}
}

// Next returns the first occurrence strictly after the supplied instant.
func (schedule Schedule) Next(after time.Time) (time.Time, error) {
	parsed, err := schedule.parse()
	if err != nil {
		return time.Time{}, err
	}
	next := parsed.Next(after.UTC())
	if next.IsZero() {
		return time.Time{}, nil
	}
	return next.UTC(), nil
}

func (schedule Schedule) normalized() Schedule {
	if schedule.Kind == ScheduleHTTP {
		return Schedule{Kind: ScheduleHTTP}
	}
	normalized := schedule
	normalized.Expression = strings.TrimSpace(normalized.Expression)
	normalized.TimeZone = strings.TrimSpace(normalized.TimeZone)
	if normalized.AnchorAt != nil {
		value := normalized.AnchorAt.UTC()
		normalized.AnchorAt = &value
	}
	if normalized.At != nil {
		value := normalized.At.UTC()
		normalized.At = &value
	}
	return normalized
}
