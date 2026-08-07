package gateway

import (
	"math"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSessionMetricTrackerRecordsObservedTTFTAndTPSInputs(t *testing.T) {
	start := time.UnixMilli(1_786_000_000_000)
	var tracker sessionMetricTracker
	if sample := tracker.observe(
		[]byte(`{"type":"turn_start","turnIndex":7}`),
		start,
	); sample != nil {
		t.Fatalf("turn_start sample = %#v, want nil", sample)
	}
	// message_start is emitted after provider response headers, before content;
	// it must not be mistaken for the first generated token.
	tracker.observe(
		[]byte(`{"type":"message_start","message":{"role":"assistant"}}`),
		start.Add(500*time.Millisecond),
	)
	tracker.observe(
		[]byte(`{"type":"message_update","assistantMessageEvent":{"type":"text_start"}}`),
		start.Add(1200*time.Millisecond),
	)
	sample := tracker.observe(
		[]byte(`{"type":"message_end","message":{"role":"assistant","provider":"opencode-go","model":"kimi-k2.6","stopReason":"toolUse","usage":{"output":90}}}`),
		start.Add(4200*time.Millisecond),
	)
	if sample == nil {
		t.Fatal("message_end sample = nil")
	}
	if sample.TurnIndex != 7 || sample.TTFTMillis != 1200 || sample.GenerationMillis != 3000 ||
		sample.OutputTokens != 90 || sample.Provider != "opencode-go" || sample.Model != "kimi-k2.6" {
		t.Fatalf("sample = %#v", sample)
	}
}

func TestSessionMetricTrackerSkipsUnmeasurableCalls(t *testing.T) {
	start := time.UnixMilli(1_786_000_000_000)
	var tracker sessionMetricTracker
	tracker.observe([]byte(`{"type":"turn_start","turnIndex":1}`), start)
	tracker.observe(
		[]byte(`{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"x"}}`),
		start.Add(time.Second),
	)
	if sample := tracker.observe(
		[]byte(`{"type":"message_end","message":{"role":"assistant","stopReason":"aborted","usage":{"output":10}}}`),
		start.Add(2*time.Second),
	); sample != nil {
		t.Fatalf("aborted sample = %#v, want nil", sample)
	}

	tracker.observe([]byte(`{"type":"turn_start","turnIndex":2}`), start.Add(3*time.Second))
	if sample := tracker.observe(
		[]byte(`{"type":"message_end","message":{"role":"assistant","stopReason":"stop","usage":{"output":10}}}`),
		start.Add(4*time.Second),
	); sample != nil {
		t.Fatalf("sample without first output = %#v, want nil", sample)
	}
}

func TestSessionStorePersistsAndSummarizesMetrics(t *testing.T) {
	store, err := newSessionStore(t.TempDir())
	if err != nil {
		t.Fatalf("create store: %v", err)
	}
	workspaceID, err := newSessionID()
	if err != nil {
		t.Fatal(err)
	}
	created, dir, err := store.create(workspaceID)
	if err != nil {
		t.Fatalf("create session: %v", err)
	}
	empty, err := store.metrics(created.ID)
	if err != nil {
		t.Fatalf("summarize empty metrics: %v", err)
	}
	if empty.SampleCount != 0 || empty.TotalOutputTokens != 0 || empty.TotalGenerationMillis != 0 ||
		empty.AverageTPS != nil || empty.AverageTTFTMillis != nil {
		t.Fatalf("empty summary = %#v", empty)
	}
	samples := []sessionMetricSample{
		{Version: 1, TTFTMillis: 1000, GenerationMillis: 4000, OutputTokens: 100},
		{Version: 1, TTFTMillis: 3000, GenerationMillis: 1000, OutputTokens: 50},
	}
	for _, sample := range samples {
		if err := store.appendMetric(created.ID, sample); err != nil {
			t.Fatalf("append metric: %v", err)
		}
	}
	path := filepath.Join(dir, sessionMetricsFileName)
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat metrics: %v", err)
	}
	if got := info.Mode().Perm(); got != 0o600 {
		t.Fatalf("metrics permissions = %o, want 600", got)
	}

	// An interrupted append without a trailing LF is ignored on recovery.
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		t.Fatalf("open metrics tail: %v", err)
	}
	if _, err := file.WriteString(`{"v":1`); err != nil {
		t.Fatalf("write partial metric: %v", err)
	}
	if err := file.Close(); err != nil {
		t.Fatalf("close partial metric: %v", err)
	}

	summary, err := store.metrics(created.ID)
	if err != nil {
		t.Fatalf("summarize metrics: %v", err)
	}
	if summary.SampleCount != 2 || summary.TotalOutputTokens != 150 || summary.TotalGenerationMillis != 5000 {
		t.Fatalf("summary totals = %#v", summary)
	}
	if summary.AverageTPS == nil || math.Abs(*summary.AverageTPS-30) > 0.0001 {
		t.Fatalf("average TPS = %v, want 30", summary.AverageTPS)
	}
	if summary.AverageTTFTMillis == nil || math.Abs(*summary.AverageTTFTMillis-2000) > 0.0001 {
		t.Fatalf("average TTFT = %v, want 2000", summary.AverageTTFTMillis)
	}

	// The next append repairs the incomplete tail instead of concatenating a
	// valid record onto it.
	if err := store.appendMetric(created.ID, sessionMetricSample{
		Version:          1,
		TTFTMillis:       500,
		GenerationMillis: 5000,
		OutputTokens:     100,
	}); err != nil {
		t.Fatalf("append after partial metric: %v", err)
	}
	summary, err = store.metrics(created.ID)
	if err != nil {
		t.Fatalf("summarize repaired metrics: %v", err)
	}
	if summary.SampleCount != 3 || summary.TotalOutputTokens != 250 || summary.TotalGenerationMillis != 10000 {
		t.Fatalf("repaired summary totals = %#v", summary)
	}
	if summary.AverageTPS == nil || math.Abs(*summary.AverageTPS-25) > 0.0001 {
		t.Fatalf("repaired average TPS = %v, want 25", summary.AverageTPS)
	}
	if summary.AverageTTFTMillis == nil || math.Abs(*summary.AverageTTFTMillis-1500) > 0.0001 {
		t.Fatalf("repaired average TTFT = %v, want 1500", summary.AverageTTFTMillis)
	}
}
