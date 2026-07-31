<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

export interface MenuItem {
  value: string
  label: string
  hint?: string
}

const props = defineProps<{
  items: MenuItem[]
  current: string | null
  buttonLabel: string
  valueLabel?: string
  disabled?: boolean
}>()

const emit = defineEmits<{ select: [value: string] }>()

const open = ref(false)
const root = ref<HTMLElement | null>(null)

function onDocClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}

onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))

function pick(value: string) {
  open.value = false
  emit('select', value)
}
</script>

<template>
  <div ref="root" class="dropdown">
    <button
      class="dropdown-btn"
      :class="{ open }"
      :disabled="disabled"
      :title="current ?? buttonLabel"
      @click="open = !open"
    >
      <span class="dropdown-btn-label">{{ buttonLabel }}</span>
      <span class="dropdown-btn-value">{{ valueLabel ?? current ?? '—' }}</span>
      <svg class="chevron" :class="{ rotated: open }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="m6 9 6 6 6-6" />
      </svg>
    </button>

    <div v-if="open" class="dropdown-panel">
      <button
        v-for="it in items"
        :key="it.value"
        class="dropdown-item"
        :class="{ current: it.value === current }"
        @click="pick(it.value)"
      >
        <svg v-if="it.value === current" class="check" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
          <path d="M20 6 9 17l-5-5" />
        </svg>
        <span v-else class="check-placeholder" />
        <span class="dropdown-item-label">{{ it.label }}</span>
        <span v-if="it.hint" class="dropdown-item-hint">{{ it.hint }}</span>
      </button>
      <div v-if="!items.length" class="dropdown-empty">无可用项</div>
    </div>
  </div>
</template>
