<script setup lang="ts">
import { computed, ref } from 'vue'
import { renderMarkdown } from '../lib/markdown'
import { formatTime, type AssistantItem, type StatusItem, type ToolItem, type UserItem } from '../lib/types'
import ToolCallCard from './ToolCallCard.vue'

const props = defineProps<{ item: UserItem | AssistantItem | ToolItem | StatusItem }>()

const isUser = computed(() => props.item.kind === 'user')
const isAssistant = computed(() => props.item.kind === 'assistant')
const isTool = computed(() => props.item.kind === 'tool')
const isStatus = computed(() => props.item.kind === 'status')

const thinkingOpen = ref(false)

const streaming = computed(() => isAssistant.value && (props.item as AssistantItem).streaming)
const stopReasonLabel = computed(() => {
  const it = props.item as AssistantItem
  if (!it.stopReason || it.stopReason === 'stop') return ''
  const map: Record<string, string> = {
    length: '已到达长度上限',
    toolUse: '工具调用后继续',
    error: '出错',
    aborted: '已中止',
  }
  return map[it.stopReason] ?? it.stopReason
})

const statusText = computed(() => (isStatus.value ? (props.item as StatusItem).text : ''))
const statusTone = computed(() => (isStatus.value ? (props.item as StatusItem).tone : 'info'))
</script>

<template>
  <!-- user message -->
  <div v-if="isUser" class="msg-row user-row">
    <div class="msg-bubble user-bubble">
      <div class="msg-user-text">{{ (item as UserItem).text }}</div>
      <div class="msg-time">{{ formatTime((item as UserItem).ts) }}</div>
    </div>
    <div class="avatar user-avatar">你</div>
  </div>

  <!-- assistant message -->
  <div v-else-if="isAssistant" class="msg-row assistant-row">
    <div class="avatar pi-avatar">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 2 2 7l10 5 10-5-10-5z" />
        <path d="m2 17 10 5 10-5" />
        <path d="m2 12 10 5 10-5" />
      </svg>
    </div>
    <div class="msg-body">
      <!-- thinking blocks -->
      <template v-for="(blk, idx) in (item as AssistantItem).blocks" :key="idx">
        <div v-if="blk.kind === 'thinking'" class="thinking-block">
          <button class="thinking-head" @click="thinkingOpen = !thinkingOpen">
            <svg class="thinking-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3" />
              <path d="M12 17h.01" />
              <circle cx="12" cy="12" r="10" />
            </svg>
            <span>思考过程</span>
            <svg class="chevron" :class="{ rotated: thinkingOpen }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="m6 9 6 6 6-6" />
            </svg>
          </button>
          <div v-if="thinkingOpen" class="thinking-content markdown" v-html="renderMarkdown(blk.text ?? '')" />
        </div>

        <div v-else-if="blk.kind === 'text'" class="msg-text markdown" v-html="renderMarkdown(blk.text ?? '')" />

        <ToolCallCard v-else-if="blk.kind === 'toolcall' && blk.tool" :key="idx" :tool="blk.tool" />
      </template>

      <div v-if="streaming" class="streaming-caret" />

      <div v-if="stopReasonLabel" class="stop-reason">{{ stopReasonLabel }}</div>

      <div v-if="(item as AssistantItem).model" class="msg-meta">
        {{ (item as AssistantItem).model }}{{ (item as AssistantItem).ts ? ' · ' + formatTime((item as AssistantItem).ts) : '' }}
      </div>
    </div>
  </div>

  <!-- standalone tool item -->
  <div v-else-if="isTool" class="msg-row tool-row">
    <div class="avatar tool-avatar">⚙</div>
    <div class="msg-body">
      <ToolCallCard :tool="(item as ToolItem).tool" default-open />
    </div>
  </div>

  <!-- status chip -->
  <div v-else-if="isStatus" class="status-chip" :class="statusTone">
    {{ statusText }}
    <span class="status-time">{{ formatTime((item as StatusItem).ts) }}</span>
  </div>
</template>
