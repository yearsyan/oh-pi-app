package gateway

import (
	"bytes"
	"encoding/json"
	"fmt"
)

type replayPayloadMetadata struct {
	outputType string
	role       string
	toolCallID string
}

type compactedReplayRecord struct {
	record persistedReplayRecord
	meta   replayPayloadMetadata
}

// replayLogCompactor keeps replay behavior while discarding superseded
// streaming states. Sequence numbers are intentionally left sparse: replay
// cursors are high-water marks, not contiguous record indexes.
type replayLogCompactor struct {
	records           []compactedReplayRecord
	assistantStartSeq uint64
}

func (c *replayLogCompactor) add(record persistedReplayRecord) error {
	payload, meta, err := compactReplayPayload(record.Payload)
	if err != nil {
		return fmt.Errorf("compact replay payload at sequence %d: %w", record.Seq, err)
	}
	record.Payload = payload

	switch meta.outputType {
	case "message_start":
		if meta.role == "assistant" {
			c.assistantStartSeq = record.Seq
		}
	case "message_end":
		if meta.role == "assistant" && c.assistantStartSeq != 0 {
			start := c.assistantStartSeq
			c.discard(func(existing compactedReplayRecord) bool {
				return existing.record.Seq >= start &&
					((existing.meta.outputType == "message_start" && existing.meta.role == "assistant") ||
						existing.meta.outputType == "message_update")
			})
			c.assistantStartSeq = 0
		}
	case "tool_execution_update":
		if meta.toolCallID != "" {
			c.discard(func(existing compactedReplayRecord) bool {
				return existing.meta.outputType == "tool_execution_update" &&
					existing.meta.toolCallID == meta.toolCallID
			})
		}
	case "tool_execution_end":
		if meta.toolCallID != "" {
			c.discard(func(existing compactedReplayRecord) bool {
				return existing.meta.outputType == "tool_execution_update" &&
					existing.meta.toolCallID == meta.toolCallID
			})
		}
	case "queue_update", "session_info_changed":
		c.discard(func(existing compactedReplayRecord) bool {
			return existing.meta.outputType == meta.outputType
		})
	}

	c.records = append(c.records, compactedReplayRecord{record: record, meta: meta})
	return nil
}

func (c *replayLogCompactor) discard(remove func(compactedReplayRecord) bool) {
	kept := c.records[:0]
	for _, record := range c.records {
		if !remove(record) {
			kept = append(kept, record)
		}
	}
	c.records = kept
}

// compactReplayPayload strips cumulative snapshots that pi repeats on every
// message_update. The app consumes assistantMessageEvent; the top-level
// message is redundant, and partial is needed only by the start event.
func compactReplayPayload(payload []byte) (json.RawMessage, replayPayloadMetadata, error) {
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(payload, &fields); err != nil {
		return nil, replayPayloadMetadata{}, err
	}
	var meta replayPayloadMetadata
	if raw := fields["type"]; raw != nil {
		if err := json.Unmarshal(raw, &meta.outputType); err != nil {
			return nil, replayPayloadMetadata{}, fmt.Errorf("decode output type: %w", err)
		}
	}
	if raw := fields["message"]; raw != nil &&
		(meta.outputType == "message_start" || meta.outputType == "message_end") {
		var message struct {
			Role string `json:"role"`
		}
		if err := json.Unmarshal(raw, &message); err != nil {
			return nil, replayPayloadMetadata{}, fmt.Errorf("decode message metadata: %w", err)
		}
		meta.role = message.Role
	}
	if raw := fields["toolCallId"]; raw != nil {
		if err := json.Unmarshal(raw, &meta.toolCallID); err != nil {
			return nil, replayPayloadMetadata{}, fmt.Errorf("decode tool call ID: %w", err)
		}
	}

	if meta.outputType == "turn_end" {
		// message_end is the authoritative final assistant snapshot. pi repeats
		// that same full message on turn_end, which the app treats as lifecycle
		// metadata only.
		delete(fields, "message")
	} else if meta.outputType != "message_update" {
		return bytes.Clone(payload), meta, nil
	} else {
		delete(fields, "message")
		if raw := fields["assistantMessageEvent"]; raw != nil {
			var update map[string]json.RawMessage
			if err := json.Unmarshal(raw, &update); err != nil {
				return nil, replayPayloadMetadata{}, fmt.Errorf("decode assistant update: %w", err)
			}
			var subtype string
			if rawType := update["type"]; rawType != nil {
				if err := json.Unmarshal(rawType, &subtype); err != nil {
					return nil, replayPayloadMetadata{}, fmt.Errorf("decode assistant update type: %w", err)
				}
			}
			if subtype != "start" {
				delete(update, "partial")
			}
			encoded, err := json.Marshal(update)
			if err != nil {
				return nil, replayPayloadMetadata{}, fmt.Errorf("encode assistant update: %w", err)
			}
			fields["assistantMessageEvent"] = encoded
		}
	}
	encoded, err := json.Marshal(fields)
	if err != nil {
		return nil, replayPayloadMetadata{}, fmt.Errorf("encode replay payload: %w", err)
	}
	return encoded, meta, nil
}
