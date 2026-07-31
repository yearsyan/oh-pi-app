<script setup lang="ts">
import { ref, watch } from 'vue'
import { useStore } from '../lib/store'
import type { ExtensionUiRequest } from '../lib/types'

const store = useStore()
const { state } = store

const value = ref('')

watch(
  () => state.dialog,
  (d) => {
    if (d) value.value = d.request.prefill ?? ''
  },
)

function methodTitle(m: ExtensionUiRequest['method'] | undefined): string {
  switch (m) {
    case 'select':
      return '选择'
    case 'confirm':
      return '确认'
    case 'input':
      return '输入'
    case 'editor':
      return '编辑'
    default:
      return '请求'
  }
}

function confirm() {
  const d = state.dialog
  if (!d) return
  if (d.request.method === 'confirm') {
    store.respondDialog({ confirmed: true })
  } else {
    store.respondDialog({ value: value.value })
  }
}

function cancel() {
  store.respondDialog({ cancelled: true })
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') cancel()
  if (e.key === 'Enter' && !e.shiftKey && d()?.request.method !== 'editor') {
    e.preventDefault()
    confirm()
  }
}

function d() {
  return state.dialog
}
</script>

<template>
  <teleport to="body">
    <div v-if="state.dialog" class="dialog-backdrop" @click.self="cancel">
      <div class="dialog" @keydown="onKeydown">
        <div class="dialog-title">
          {{ d()?.request.title || methodTitle(d()?.request.method ?? 'input') }}
        </div>

        <template v-if="d()?.request.method === 'select'">
          <div class="dialog-options">
            <button
              v-for="(opt, i) in d()?.request.options ?? []"
              :key="i"
              class="dialog-option"
              @click="store.respondDialog({ value: opt })"
            >
              {{ opt }}
            </button>
          </div>
        </template>

        <template v-else-if="d()?.request.method === 'confirm'">
          <p class="dialog-message">{{ d()?.request.message }}</p>
          <div class="dialog-actions">
            <button class="btn-ghost" @click="cancel">取消</button>
            <button class="btn-primary" @click="confirm">确认</button>
          </div>
        </template>

        <template v-else>
          <input
            v-if="d()?.request.method === 'input'"
            v-model="value"
            class="dialog-input"
            :placeholder="d()?.request.placeholder"
            autofocus
            @keydown.enter.prevent="confirm"
          />
          <textarea
            v-else
            v-model="value"
            class="dialog-textarea"
            :placeholder="d()?.request.placeholder"
            rows="10"
          />
          <div class="dialog-actions">
            <button class="btn-ghost" @click="cancel">取消</button>
            <button class="btn-primary" @click="confirm">确定</button>
          </div>
        </template>
      </div>
    </div>
  </teleport>
</template>
