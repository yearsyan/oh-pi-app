<script setup lang="ts">
import { computed } from 'vue'
import { useStore } from '../lib/store'
import { formatRelative } from '../lib/types'

const store = useStore()
const { state } = store

const connDot = computed(() => {
  switch (state.conn) {
    case 'ready':
      return 'online'
    case 'connecting':
      return 'pending'
    case 'error':
      return 'error'
    default:
      return 'offline'
  }
})

const connLabel = computed(() => {
  switch (state.conn) {
    case 'ready':
      return '已连接'
    case 'connecting':
      return '连接中…'
    case 'error':
      return '连接错误'
    default:
      return '未连接'
  }
})

function sessionTitle(s: { id: string; name: string }) {
  return s.name || s.id.slice(0, 8)
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-brand">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 2 2 7l10 5 10-5-10-5z" />
        <path d="m2 17 10 5 10-5" />
        <path d="m2 12 10 5 10-5" />
      </svg>
      <span>pi2ws</span>
    </div>

    <button class="new-chat-btn" @click="store.newChat()" :disabled="state.conn === 'connecting'">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 5v14M5 12h14" />
      </svg>
      新建会话
    </button>

    <div class="session-list">
      <div class="session-list-title">历史会话</div>
      <button
        v-for="s in state.sessions"
        :key="s.id"
        class="session-item"
        :class="{ active: s.id === state.activeSessionId }"
        @click="store.attachSession(s.id)"
      >
        <span class="session-dot" :class="{ live: s.id === state.activeSessionId && state.conn === 'ready' }" />
        <span class="session-name">{{ sessionTitle(s) }}</span>
        <span class="session-time">{{ formatRelative(s.lastActive) }}</span>
        <span class="session-del" title="删除会话" @click.stop="store.removeSession(s.id)">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
          </svg>
        </span>
      </button>
      <div v-if="!state.sessions.length" class="session-empty">暂无会话。新建一个开始对话。</div>
    </div>

    <div class="sidebar-footer">
      <div class="conn-row">
        <span class="conn-dot" :class="connDot" />
        <span>{{ connLabel }}</span>
        <span v-if="state.connDetail" class="conn-detail">{{ state.connDetail }}</span>
      </div>
      <button class="settings-btn" @click="store.openSettings()">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
        设置
      </button>
    </div>
  </aside>
</template>
