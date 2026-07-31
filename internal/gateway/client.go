package gateway

import (
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type wsClient struct {
	conn         *websocket.Conn
	send         chan []byte
	done         chan struct{}
	writeTimeout time.Duration
	pongTimeout  time.Duration
	closeOnce    sync.Once
}

func newWSClient(conn *websocket.Conn, queueSize int, writeTimeout, pongTimeout time.Duration) *wsClient {
	return &wsClient{
		conn:         conn,
		send:         make(chan []byte, queueSize),
		done:         make(chan struct{}),
		writeTimeout: writeTimeout,
		pongTimeout:  pongTimeout,
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
		case message := <-c.send:
			if err := c.conn.SetWriteDeadline(time.Now().Add(c.writeTimeout)); err != nil {
				c.abort()
				return
			}
			if err := c.conn.WriteMessage(websocket.TextMessage, message); err != nil {
				c.abort()
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
