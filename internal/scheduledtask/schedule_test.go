package scheduledtask

import (
	"testing"
	"time"
)

func TestCronScheduleUsesFiveFieldsAndTimeZone(t *testing.T) {
	anchor := time.Date(2026, time.August, 12, 0, 30, 0, 0, time.UTC)
	next, err := (Schedule{
		Kind:       ScheduleCron,
		Expression: "0 9 * * 1-5",
		TimeZone:   "Asia/Shanghai",
	}).Next(anchor)
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	want := time.Date(2026, time.August, 12, 1, 0, 0, 0, time.UTC)
	if !next.Equal(want) {
		t.Fatalf("next = %s, want %s", next, want)
	}
}

func TestCronScheduleRejectsSecondsField(t *testing.T) {
	_, err := (Schedule{
		Kind:       ScheduleCron,
		Expression: "0 0 9 * * 1-5",
		TimeZone:   "Asia/Shanghai",
	}).Next(time.Now())
	if err == nil {
		t.Fatal("six-field cron expression was accepted")
	}
}

func TestIntervalScheduleStaysAnchored(t *testing.T) {
	anchor := time.Date(2026, time.August, 12, 1, 0, 0, 0, time.UTC)
	next, err := (Schedule{
		Kind:         ScheduleInterval,
		EverySeconds: int64((6 * time.Hour) / time.Second),
		AnchorAt:     &anchor,
	}).Next(anchor.Add(7*time.Hour + 12*time.Minute))
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	want := anchor.Add(12 * time.Hour)
	if !next.Equal(want) {
		t.Fatalf("next = %s, want %s", next, want)
	}
}

func TestOnceScheduleHasNoOccurrenceAfterTarget(t *testing.T) {
	at := time.Date(2026, time.August, 13, 10, 0, 0, 0, time.UTC)
	schedule := Schedule{Kind: ScheduleOnce, At: &at}
	if next, err := schedule.Next(at.Add(-time.Second)); err != nil || !next.Equal(at) {
		t.Fatalf("before target: next=%s err=%v", next, err)
	}
	if next, err := schedule.Next(at); err != nil || !next.IsZero() {
		t.Fatalf("at target: next=%s err=%v", next, err)
	}
}
