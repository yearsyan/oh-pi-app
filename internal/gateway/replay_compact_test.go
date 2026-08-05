package gateway

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCompactReplayPayloadDropsRepeatedMessageSnapshots(t *testing.T) {
	payload := marshalTestJSON(t, map[string]any{
		"type": "message_update",
		"assistantMessageEvent": map[string]any{
			"type":    "thinking_delta",
			"delta":   "new",
			"partial": map[string]any{"content": strings.Repeat("old", 10_000)},
		},
		"message": map[string]any{"content": strings.Repeat("old", 10_000)},
	})

	compacted, meta, err := compactReplayPayload(payload)
	if err != nil {
		t.Fatalf("compact replay payload: %v", err)
	}
	if meta.outputType != "message_update" {
		t.Fatalf("output type = %q, want message_update", meta.outputType)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(compacted, &fields); err != nil {
		t.Fatalf("decode compacted payload: %v", err)
	}
	if _, found := fields["message"]; found {
		t.Fatal("compacted message_update retained top-level message snapshot")
	}
	var update map[string]json.RawMessage
	if err := json.Unmarshal(fields["assistantMessageEvent"], &update); err != nil {
		t.Fatalf("decode compacted assistant update: %v", err)
	}
	if _, found := update["partial"]; found {
		t.Fatal("compacted thinking_delta retained partial snapshot")
	}
	var delta string
	if err := json.Unmarshal(update["delta"], &delta); err != nil || delta != "new" {
		t.Fatalf("compacted delta = %q, error = %v", delta, err)
	}
	if len(compacted) >= len(payload)/10 {
		t.Fatalf("compacted bytes = %d, original = %d", len(compacted), len(payload))
	}
}

func TestCompactReplayPayloadKeepsStartPartial(t *testing.T) {
	payload := marshalTestJSON(t, map[string]any{
		"type": "message_update",
		"assistantMessageEvent": map[string]any{
			"type":    "start",
			"partial": map[string]any{"content": []any{map[string]any{"type": "text", "text": "existing"}}},
		},
		"message": map[string]any{"role": "assistant"},
	})
	compacted, _, err := compactReplayPayload(payload)
	if err != nil {
		t.Fatalf("compact replay start: %v", err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(compacted, &fields); err != nil {
		t.Fatalf("decode compacted start: %v", err)
	}
	var update map[string]json.RawMessage
	if err := json.Unmarshal(fields["assistantMessageEvent"], &update); err != nil {
		t.Fatalf("decode compacted start update: %v", err)
	}
	if _, found := update["partial"]; !found {
		t.Fatal("compacted start lost the partial snapshot needed for reconstruction")
	}
}

func TestCompactReplayPayloadDropsTurnEndMessageDuplicate(t *testing.T) {
	payload := marshalTestJSON(t, map[string]any{
		"type":    "turn_end",
		"message": map[string]any{"role": "assistant", "content": strings.Repeat("done", 10_000)},
		"reason":  "complete",
	})
	compacted, _, err := compactReplayPayload(payload)
	if err != nil {
		t.Fatalf("compact turn_end: %v", err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(compacted, &fields); err != nil {
		t.Fatalf("decode compacted turn_end: %v", err)
	}
	if _, found := fields["message"]; found {
		t.Fatal("compacted turn_end retained the final message duplicate")
	}
	if _, found := fields["reason"]; !found {
		t.Fatal("compacted turn_end lost lifecycle metadata")
	}
}

func TestRewriteReplayLogKeepsFinalStatesAndActiveTail(t *testing.T) {
	path := filepath.Join(t.TempDir(), replayLogFileName)
	records := []persistedReplayRecord{
		testReplayRecord(t, 1, map[string]any{"type": "agent_start"}),
		testReplayRecord(t, 2, map[string]any{"type": "message_start", "message": map[string]any{"role": "assistant"}}),
		testReplayRecord(t, 3, testAssistantDelta("thinking_delta", "one")),
		testReplayRecord(t, 4, testAssistantDelta("thinking_delta", "two")),
		testReplayRecord(t, 5, map[string]any{
			"type": "message_end", "message": map[string]any{"role": "assistant", "content": []any{}},
		}),
		testReplayRecord(t, 6, map[string]any{"type": "tool_execution_start", "toolCallId": "call-1"}),
		testReplayRecord(t, 7, map[string]any{"type": "tool_execution_update", "toolCallId": "call-1", "partialResult": "old"}),
		testReplayRecord(t, 8, map[string]any{"type": "tool_execution_update", "toolCallId": "call-1", "partialResult": "new"}),
		testReplayRecord(t, 9, map[string]any{"type": "tool_execution_end", "toolCallId": "call-1", "result": "done"}),
		testReplayRecord(t, 10, map[string]any{"type": "queue_update", "steering": []string{"old"}}),
		testReplayRecord(t, 11, map[string]any{"type": "queue_update", "steering": []string{"new"}}),
		testReplayRecord(t, 12, map[string]any{"type": "message_start", "message": map[string]any{"role": "assistant"}}),
		testReplayRecord(t, 13, testAssistantDelta("text_delta", "active")),
	}
	file, err := os.Create(path)
	if err != nil {
		t.Fatalf("create replay log: %v", err)
	}
	writer := bufio.NewWriter(file)
	for _, record := range records {
		line := marshalTestJSON(t, record)
		if _, err := writer.Write(append(line, '\n')); err != nil {
			t.Fatalf("write replay record: %v", err)
		}
	}
	if _, err := writer.WriteString(`{"seq":14,"payload":`); err != nil {
		t.Fatalf("write partial tail: %v", err)
	}
	if err := writer.Flush(); err != nil {
		t.Fatalf("flush replay log: %v", err)
	}
	if err := file.Close(); err != nil {
		t.Fatalf("close replay log: %v", err)
	}

	lastSeq, err := rewriteReplayLog(path, 0)
	if err != nil {
		t.Fatalf("rewrite replay log: %v", err)
	}
	if lastSeq != 13 {
		t.Fatalf("last sequence = %d, want 13", lastSeq)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read compacted replay log: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	wantSeq := []uint64{1, 5, 6, 9, 11, 12, 13}
	if len(lines) != len(wantSeq) {
		t.Fatalf("compacted records = %d, want %d: %s", len(lines), len(wantSeq), data)
	}
	for index, line := range lines {
		var record persistedReplayRecord
		if err := json.Unmarshal([]byte(line), &record); err != nil {
			t.Fatalf("decode compacted record %d: %v", index, err)
		}
		if record.Seq != wantSeq[index] {
			t.Fatalf("compacted sequence[%d] = %d, want %d", index, record.Seq, wantSeq[index])
		}
	}
	if strings.Contains(string(data), `"partial"`) || strings.Contains(string(data), `"message":{"content":"snapshot"}`) {
		t.Fatalf("compacted active tail retained cumulative snapshots: %s", data)
	}
}

func TestRewriteReplayLogMakesActiveThinkingGrowthLinear(t *testing.T) {
	path := filepath.Join(t.TempDir(), replayLogFileName)
	file, err := os.Create(path)
	if err != nil {
		t.Fatalf("create replay log: %v", err)
	}
	writer := bufio.NewWriter(file)
	writeRecord := func(record persistedReplayRecord) {
		t.Helper()
		line := marshalTestJSON(t, record)
		if _, err := writer.Write(append(line, '\n')); err != nil {
			t.Fatalf("write replay record: %v", err)
		}
	}
	writeRecord(testReplayRecord(t, 1, map[string]any{
		"type": "message_start", "message": map[string]any{"role": "assistant"},
	}))
	const updates = 256
	for index := 1; index <= updates; index++ {
		cumulative := strings.Repeat("x", index*128)
		writeRecord(testReplayRecord(t, uint64(index+1), map[string]any{
			"type": "message_update",
			"assistantMessageEvent": map[string]any{
				"type": "thinking_delta", "delta": "x", "partial": map[string]any{"content": cumulative},
			},
			"message": map[string]any{"content": cumulative},
		}))
	}
	if err := writer.Flush(); err != nil {
		t.Fatalf("flush replay log: %v", err)
	}
	if err := file.Close(); err != nil {
		t.Fatalf("close replay log: %v", err)
	}
	before, err := os.Stat(path)
	if err != nil {
		t.Fatalf("inspect original replay log: %v", err)
	}
	if before.Size() < 8<<20 {
		t.Fatalf("quadratic replay fixture = %d bytes, want at least 8 MiB", before.Size())
	}

	lastSeq, err := rewriteReplayLog(path, 0)
	if err != nil {
		t.Fatalf("rewrite active replay log: %v", err)
	}
	if lastSeq != updates+1 {
		t.Fatalf("last sequence = %d, want %d", lastSeq, updates+1)
	}
	after, err := os.Stat(path)
	if err != nil {
		t.Fatalf("inspect compacted replay log: %v", err)
	}
	if after.Size() >= 128<<10 {
		t.Fatalf("compacted active replay = %d bytes, want less than 128 KiB", after.Size())
	}
}

func testAssistantDelta(subtype, delta string) map[string]any {
	return map[string]any{
		"type": "message_update",
		"assistantMessageEvent": map[string]any{
			"type": subtype, "delta": delta,
			"partial": map[string]any{"content": "snapshot"},
		},
		"message": map[string]any{"content": "snapshot"},
	}
}

func testReplayRecord(t *testing.T, seq uint64, payload any) persistedReplayRecord {
	t.Helper()
	return persistedReplayRecord{Seq: seq, Payload: marshalTestJSON(t, payload)}
}

func marshalTestJSON(t *testing.T, value any) []byte {
	t.Helper()
	data, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal test JSON: %v", err)
	}
	return data
}
