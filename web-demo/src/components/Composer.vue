<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { useStore } from '../lib/store'

const store = useStore()
const { state } = store

const draft = ref('')
const inputEl = ref<HTMLTextAreaElement | null>(null)

function autoGrow() {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

function submit() {
  const text = draft.value
  if (!text.trim()) return
  store.sendPrompt(text)
  draft.value = ''
  nextTick(autoGrow)
  inputEl.value?.focus()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submit()
  }
}

function stop() {
  store.abort()
}
</script>

<template>
  <div class="composer-wrap">
    <div v-if="state.steeringQueue.length || state.followUpQueue.length" class="queue-hint">
      <span v-for="(q, i) in state.steeringQueue" :key="'s' + i" class="queue-chip steer">steer: {{ q }}</span>
      <span v-for="(q, i) in state.followUpQueue" :key="'f' + i" class="queue-chip follow">排队: {{ q }}</span>
    </div>

    <div class="composer">
      <textarea
        ref="inputEl"
        v-model="draft"
        rows="1"
        class="composer-input"
        placeholder="发送消息给 pi…（Enter 发送，Shift+Enter 换行）"
        :disabled="state.conn !== 'ready'"
        @keydown="onKeydown"
        @input="autoGrow"
      />
      <div class="composer-actions">
        <button v-if="state.isStreaming" class="stop-btn" title="中止当前操作" @click="stop">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <rect x="6" y="6" width="12" height="12" rx="2" />
          </svg>
          停止
        </button>
        <button
          class="send-btn"
          :disabled="state.conn !== 'ready' || !draft.trim()"
          title="发送"
          @click="submit"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="m22 2-7 20-4-9-9-4 20-7z" />
            <path d="M22 2 11 13" />
          </svg>
        </button>
      </div>
    </div>
    <div class="composer-hint">
      {{ state.isStreaming ? 'Agent 正在运行，发送的消息将作为 steer 排队插入' : '空闲' }}
    </div>
  </div>
</template>
