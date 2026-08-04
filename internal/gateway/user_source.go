package gateway

import (
	"encoding/json"
	"strings"
	"sync"
)

type userMessageSignature struct {
	text       string
	imageCount int
}

type userInputSource struct {
	token     uint64
	id        string
	signature userMessageSignature
}

// userSourceTracker associates user-message events emitted by pi with the
// prompt-like RPC command that caused them. Commands and events can be delayed
// or reordered by steering/follow-up behavior, so matching is content-aware
// instead of relying only on a FIFO.
type userSourceTracker struct {
	mu        sync.Mutex
	nextToken uint64
	pending   []userInputSource
	active    []userInputSource
}

func userSourceFromCommand(command []byte) (userInputSource, bool) {
	var decoded struct {
		Type    string            `json:"type"`
		ID      string            `json:"id"`
		Message string            `json:"message"`
		Images  []json.RawMessage `json:"images"`
	}
	if json.Unmarshal(command, &decoded) != nil || decoded.ID == "" {
		return userInputSource{}, false
	}
	switch decoded.Type {
	case "prompt", "steer", "follow_up":
	default:
		return userInputSource{}, false
	}
	return userInputSource{
		id: decoded.ID,
		signature: userMessageSignature{
			text:       decoded.Message,
			imageCount: len(decoded.Images),
		},
	}, true
}

func userSignatureFromMessage(content json.RawMessage) (userMessageSignature, bool) {
	if len(content) == 0 {
		return userMessageSignature{}, false
	}
	var text string
	if json.Unmarshal(content, &text) == nil {
		return userMessageSignature{text: text}, true
	}
	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if json.Unmarshal(content, &blocks) != nil {
		return userMessageSignature{}, false
	}
	texts := make([]string, 0, len(blocks))
	imageCount := 0
	for _, block := range blocks {
		switch block.Type {
		case "text":
			if block.Text != "" {
				texts = append(texts, block.Text)
			}
		case "image":
			imageCount++
		}
	}
	return userMessageSignature{text: strings.Join(texts, "\n"), imageCount: imageCount}, true
}

func (t *userSourceTracker) enqueue(source userInputSource) uint64 {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.nextToken++
	source.token = t.nextToken
	t.pending = append(t.pending, source)
	return source.token
}

func (t *userSourceTracker) rollback(token uint64) {
	if token == 0 {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	for index := len(t.pending) - 1; index >= 0; index-- {
		if t.pending[index].token == token {
			t.pending = append(t.pending[:index], t.pending[index+1:]...)
			return
		}
	}
}

func (t *userSourceTracker) reject(id string) {
	if id == "" {
		return
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	for index, source := range t.pending {
		if source.id == id {
			t.pending = append(t.pending[:index], t.pending[index+1:]...)
			return
		}
	}
}

func (t *userSourceTracker) begin(signature userMessageSignature) (string, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	index := matchingUserSource(t.pending, signature)
	if index < 0 {
		return "", false
	}
	source := t.pending[index]
	t.pending = append(t.pending[:index], t.pending[index+1:]...)
	t.active = append(t.active, source)
	return source.id, true
}

func (t *userSourceTracker) end(signature userMessageSignature) (string, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	index := matchingUserSource(t.active, signature)
	if index >= 0 {
		source := t.active[index]
		t.active = append(t.active[:index], t.active[index+1:]...)
		return source.id, true
	}

	// Be defensive if a pi version emits message_end without message_start.
	index = matchingUserSource(t.pending, signature)
	if index < 0 {
		return "", false
	}
	source := t.pending[index]
	t.pending = append(t.pending[:index], t.pending[index+1:]...)
	return source.id, true
}

func matchingUserSource(sources []userInputSource, signature userMessageSignature) int {
	for index, source := range sources {
		if source.signature == signature {
			return index
		}
	}
	return -1
}

func (s *piSession) annotateUserSource(message []byte, eventType string) []byte {
	if eventType != "message_start" && eventType != "message_end" {
		return message
	}
	var event struct {
		Message struct {
			Role    string          `json:"role"`
			Content json.RawMessage `json:"content"`
		} `json:"message"`
	}
	if json.Unmarshal(message, &event) != nil || event.Message.Role != "user" {
		return message
	}
	signature, ok := userSignatureFromMessage(event.Message.Content)
	if !ok {
		return message
	}
	var sourceID string
	if eventType == "message_start" {
		sourceID, ok = s.userSources.begin(signature)
	} else {
		sourceID, ok = s.userSources.end(signature)
	}
	if !ok {
		return message
	}

	var fields map[string]json.RawMessage
	if json.Unmarshal(message, &fields) != nil {
		return message
	}
	fields["source_id"], _ = json.Marshal(sourceID)
	annotated, err := json.Marshal(fields)
	if err != nil {
		return message
	}
	return annotated
}
