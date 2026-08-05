package gateway

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestUserSourceAnnotatesStartAndEndByPromptContent(t *testing.T) {
	session := &piSession{}
	first, ok := userSourceFromCommand([]byte(`{"id":"prompt-a","type":"prompt","message":"same later"}`))
	if !ok {
		t.Fatal("first prompt did not produce a user source")
	}
	second, ok := userSourceFromCommand([]byte(`{"id":"prompt-b","type":"prompt","message":"delivered first","images":[{"type":"image"}]}`))
	if !ok {
		t.Fatal("second prompt did not produce a user source")
	}
	session.userSources.enqueue(first)
	session.userSources.enqueue(second)

	secondEvent := []byte(`{"type":"message_start","message":{"role":"user","content":[{"type":"image","data":"..."},{"type":"text","text":"delivered first"}]}}`)
	assertSourceID(t, session.annotateUserSource(secondEvent, "message_start"), "prompt-b")
	secondEnd := []byte(`{"type":"message_end","message":{"role":"user","content":[{"type":"image","data":"..."},{"type":"text","text":"delivered first"}]}}`)
	assertSourceID(t, session.annotateUserSource(secondEnd, "message_end"), "prompt-b")

	firstEvent := []byte(`{"type":"message_start","message":{"role":"user","content":"same later"}}`)
	assertSourceID(t, session.annotateUserSource(firstEvent, "message_start"), "prompt-a")
	firstEnd := []byte(`{"type":"message_end","message":{"role":"user","content":"same later"}}`)
	assertSourceID(t, session.annotateUserSource(firstEnd, "message_end"), "prompt-a")
}

func TestRejectedPromptSourceIsNotAppliedToLaterUserEvent(t *testing.T) {
	session := &piSession{}
	source, ok := userSourceFromCommand([]byte(`{"id":"rejected","type":"prompt","message":"hello"}`))
	if !ok {
		t.Fatal("prompt did not produce a user source")
	}
	session.userSources.enqueue(source)
	session.userSources.reject("rejected")

	event := []byte(`{"type":"message_start","message":{"role":"user","content":"hello"}}`)
	assertSourceID(t, session.annotateUserSource(event, "message_start"), "")
}

func TestResponseConfirmedSlashSourceIsRemovedAndMetadataIsNotForwarded(t *testing.T) {
	session := &piSession{}
	command := []byte(`{"id":"slash","type":"prompt","message":"/skill:review","pi2ws_confirm_on_response":true}`)
	source, ok := userSourceFromCommand(command)
	if !ok || !source.confirmOnResponse {
		t.Fatal("slash prompt did not request response confirmation")
	}
	session.userSources.enqueue(source)
	session.userSources.confirmResponse("slash")
	if len(session.userSources.pending) != 0 {
		t.Fatalf("response-confirmed sources = %#v, want none", session.userSources.pending)
	}
	if stripped := stripUserSourceMetadata(command); string(stripped) == string(command) ||
		strings.Contains(string(stripped), "pi2ws_confirm_on_response") {
		t.Fatalf("stripped command = %s", stripped)
	}
}

func TestCommandsWithoutStringIDsRemainBackwardCompatible(t *testing.T) {
	for _, command := range [][]byte{
		[]byte(`{"type":"prompt","message":"hello"}`),
		[]byte(`{"id":42,"type":"prompt","message":"hello"}`),
		[]byte(`{"id":"state","type":"get_state"}`),
	} {
		if _, ok := userSourceFromCommand(command); ok {
			t.Fatalf("command unexpectedly produced a user source: %s", command)
		}
	}
}

func assertSourceID(t *testing.T, payload []byte, want string) {
	t.Helper()
	var event struct {
		SourceID string `json:"source_id"`
	}
	if err := json.Unmarshal(payload, &event); err != nil {
		t.Fatalf("decode annotated event: %v", err)
	}
	if event.SourceID != want {
		t.Fatalf("source_id = %q, want %q; event = %s", event.SourceID, want, payload)
	}
}
