package gateway

import (
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type wsClient struct {
	conn         *websocket.Conn
	send         chan []byte
	syncSend     chan syncWrite
	done         chan struct{}
	writeTimeout time.Duration
	pongTimeout  time.Duration
	replayCursor bool
	closeOnce    sync.Once
}

type syncWrite struct {
	messageType int
	message     []byte
	result      chan bool
}

func newWSClient(
	conn *websocket.Conn,
	queueSize int,
	writeTimeout, pongTimeout time.Duration,
	replayCursor bool,
) *wsClient {
	return &wsClient{
		conn:         conn,
		send:         make(chan []byte, queueSize),
		syncSend:     make(chan syncWrite),
		done:         make(chan struct{}),
		writeTimeout: writeTimeout,
		pongTimeout:  pongTimeout,
		replayCursor: replayCursor,
	}
}

// sendBlocking is used only by the bounded attach stream. Unlike enqueue it
// applies backpressure instead of dropping a client when a history page fills
// the live queue.
func (c *wsClient) sendBlocking(message []byte) bool {
	return c.sendFrameBlocking(websocket.TextMessage, message)
}

// sendBinaryBlocking writes one binary message while preserving attach-stream
// ordering and backpressure across the preceding/following text metadata.
func (c *wsClient) sendBinaryBlocking(message []byte) bool {
	return c.sendFrameBlocking(websocket.BinaryMessage, message)
}

func (c *wsClient) sendFrameBlocking(messageType int, message []byte) bool {
	request := syncWrite{
		messageType: messageType,
		message:     message,
		result:      make(chan bool, 1),
	}
	select {
	case c.syncSend <- request:
	case <-c.done:
		return false
	}
	select {
	case written := <-request.result:
		return written
	case <-c.done:
		return false
	}
}

func (c *wsClient) enqueue(message []byte) bool {
	select {
	case <-c.done:
		return false
	default:
	}

	select {
	case c.send <- message:
		return true
	default:
		c.abort()
		return false
	}
}

func (c *wsClient) writePump() {
	pingEvery := c.pongTimeout * 9 / 10
	ticker := time.NewTicker(pingEvery)
	defer ticker.Stop()

	for {
		select {
		case request := <-c.syncSend:
			written := c.writeMessage(request.messageType, request.message)
			request.result <- written
			if !written {
				return
			}
		case message := <-c.send:
			if !c.writeMessage(websocket.TextMessage, message) {
				return
			}
		case <-ticker.C:
			deadline := time.Now().Add(c.writeTimeout)
			if err := c.conn.WriteControl(websocket.PingMessage, nil, deadline); err != nil {
				c.abort()
				return
			}
		case <-c.done:
			return
		}
	}
}

func (c *wsClient) writeMessage(messageType int, message []byte) bool {
	if err := c.conn.SetWriteDeadline(time.Now().Add(c.writeTimeout)); err != nil {
		c.abort()
		return false
	}
	if err := c.conn.WriteMessage(messageType, message); err != nil {
		c.abort()
		return false
	}
	return true
}

func (c *wsClient) close(code int, reason string) {
	c.closeOnce.Do(func() {
		close(c.done)
		deadline := time.Now().Add(c.writeTimeout)
		_ = c.conn.WriteControl(
			websocket.CloseMessage,
			websocket.FormatCloseMessage(code, reason),
			deadline,
		)
		_ = c.conn.Close()
	})
}

func (c *wsClient) abort() {
	c.closeOnce.Do(func() {
		close(c.done)
		_ = c.conn.Close()
	})
}
