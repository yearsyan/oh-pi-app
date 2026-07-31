<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { registerScroller, useStore } from './lib/store'
import type { ModelInfo } from './lib/types'
import Sidebar from './components/Sidebar.vue'
import MessageItem from './components/MessageItem.vue'
import Composer from './components/Composer.vue'
import ExtensionDialog from './components/ExtensionDialog.vue'
import SettingsModal from './components/SettingsModal.vue'
import DropdownMenu, { type MenuItem } from './components/DropdownMenu.vue'

const store = useStore()
const { state } = store

const modelItems = computed<MenuItem[]>(() =>
  state.models.map((m) => ({
    value: `${m.provider ?? ''}\u0001${m.id}`,
    label: m.name || m.id,
    hint: m.provider,
  })),
)

const currentModelValue = computed(() => {
  const m = state.currentModel
  return m ? `${m.provider ?? ''}\u0001${m.id}` : null
})

const currentModelLabel = computed(() => state.currentModel?.name || state.currentModel?.id || state.model || '')

const levelItems = computed<MenuItem[]>(() => state.thinkingLevels.map((l) => ({ value: l, label: l })))

function onModelSelect(value: string) {
  const sep = value.indexOf('\u0001')
  const provider = value.slice(0, sep)
  const id = value.slice(sep + 1)
  if (id) store.setModel({ provider, id } as ModelInfo)
}

function onLevelSelect(value: string) {
  store.setThinkingLevel(value)
}

onMounted(() => {
  registerScroller(document.getElementById('message-scroll'))
})

onUnmounted(() => {
  registerScroller(null)
})
</script>

<template>
  <div class="app">
    <Sidebar />

    <main class="main">
      <header class="topbar">
        <div class="session-info">
          <span class="session-id" :title="state.activeSessionId || '未连接'">
            {{ state.sessionName || (state.activeSessionId ? state.activeSessionId.slice(0, 8) : 'pi2ws Web Demo') }}
          </span>
          <DropdownMenu
            v-if="state.models.length"
            :items="modelItems"
            :current="currentModelValue"
            :value-label="currentModelLabel"
            button-label="模型"
            :disabled="state.conn !== 'ready'"
            @select="onModelSelect"
          />
          <DropdownMenu
            v-if="state.thinkingLevels.length"
            :items="levelItems"
            :current="state.thinkingLevel"
            :value-label="state.thinkingLevel"
            button-label="思考"
            :disabled="state.conn !== 'ready'"
            @select="onLevelSelect"
          />
        </div>
        <div class="topbar-right">
          <span v-if="state.isStreaming" class="streaming-badge">运行中</span>
          <span class="conn-badge" :class="state.conn">{{ state.conn === 'ready' ? '在线' : state.conn === 'connecting' ? '连接中' : state.conn }}</span>
        </div>
      </header>

      <div id="message-scroll" class="messages">
        <div v-if="!state.items.length" class="welcome">
          <div class="welcome-logo">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 2 2 7l10 5 10-5-10-5z" />
              <path d="m2 17 10 5 10-5" />
              <path d="m2 12 10 5 10-5" />
            </svg>
          </div>
          <h1>pi2ws Web Demo</h1>
          <p>通过 WebSocket 与 pi coding agent 对话，实时查看思考过程与工具调用。</p>
          <div class="welcome-actions">
            <button class="btn-primary" @click="store.newChat()">新建会话</button>
            <button class="btn-ghost" @click="store.openSettings()">配置连接</button>
          </div>
          <p class="welcome-hint">
            在设置中填写网关地址与 token；历史会话保存在浏览器 localStorage 中，可随时恢复。
          </p>
        </div>

        <MessageItem v-for="item in state.items" :key="item.key" :item="item" />
      </div>

      <Composer />
    </main>

    <!-- toasts -->
    <div class="toasts">
      <div v-for="t in state.toasts" :key="t.id" class="toast" :class="t.kind">
        {{ t.text }}
      </div>
    </div>

    <ExtensionDialog />
    <SettingsModal />
  </div>
</template>
