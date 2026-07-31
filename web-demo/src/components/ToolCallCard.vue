<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ToolCallView } from '../lib/types'
import { formatDuration } from '../lib/types'

const props = defineProps<{ tool: ToolCallView; defaultOpen?: boolean }>()

const open = ref(props.defaultOpen ?? false)

const stateLabel = computed(() => {
  switch (props.tool.state) {
    case 'streaming':
      return '调用中'
    case 'pending':
      return '等待执行'
    case 'running':
      return '执行中'
    case 'done':
      return props.tool.isError ? '失败' : '完成'
    case 'error':
      return '失败'
  }
})

const argsPreview = computed(() => {
  const a = props.tool.args.trim()
  if (!a) return ''
  const single = a.replace(/\s+/g, ' ')
  return single.length > 120 ? single.slice(0, 120) + '…' : single
})

function copy(text: string) {
  navigator.clipboard?.writeText(text).catch(() => {})
}
</script>

<template>
  <div class="tool-card" :class="`state-${tool.state}`">
    <button class="tool-head" @click="open = !open">
      <svg class="tool-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
      </svg>
      <span class="tool-name">{{ tool.name || 'tool' }}</span>
      <span class="tool-args-preview">{{ argsPreview }}</span>
      <span class="tool-state" :class="tool.state">
        <span class="dot" />
        {{ stateLabel }}
      </span>
      <span v-if="tool.endedAt || tool.state === 'running' || tool.state === 'streaming' || tool.state === 'pending'" class="tool-duration">
        {{ tool.endedAt ? formatDuration(tool.startedAt, tool.endedAt) : formatDuration(tool.startedAt) }}
      </span>
      <svg class="chevron" :class="{ rotated: open }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="m6 9 6 6 6-6" />
      </svg>
    </button>

    <div v-if="open" class="tool-body">
      <div v-if="tool.args" class="tool-section">
        <div class="tool-section-title">
          参数
          <button class="copy-btn" @click="copy(tool.args)">复制</button>
        </div>
        <pre class="tool-args"><code>{{ tool.args }}</code></pre>
      </div>
      <div v-if="tool.output" class="tool-section">
        <div class="tool-section-title">
          输出
          <button class="copy-btn" @click="copy(tool.output)">复制</button>
        </div>
        <pre class="tool-output" :class="{ 'is-error': tool.isError }"><code>{{ tool.output }}</code></pre>
      </div>
      <div v-else-if="tool.state === 'running' || tool.state === 'pending'" class="tool-running">{{ tool.state === 'pending' ? '等待执行…' : '正在执行…' }}</div>
    </div>
  </div>
</template>
