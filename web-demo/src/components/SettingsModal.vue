<script setup lang="ts">
import { reactive } from 'vue'
import { useStore } from '../lib/store'

const store = useStore()
const { state } = store

const form = reactive({ ...state.settings })

function save() {
  store.saveSettings({
    gateway: form.gateway.trim(),
    token: form.token.trim(),
  })
  store.closeSettings()
}
</script>

<template>
  <teleport to="body">
    <div v-if="state.settingsOpen" class="dialog-backdrop" @click.self="store.closeSettings()">
      <div class="dialog">
        <div class="dialog-title">连接设置</div>
        <label class="field">
          <span>网关地址（留空 = 同源 /ws 代理）</span>
          <input v-model="form.gateway" class="dialog-input" placeholder="留空或如 ws://127.0.0.1:8080" spellcheck="false" />
        </label>
        <label class="field">
          <span>Token</span>
          <input v-model="form.token" class="dialog-input" type="password" placeholder="PI2WS_TOKEN" spellcheck="false" />
        </label>
        <p class="field-hint">
          网关留空时走 vite 开发服务器的 /ws 反向代理（同源，无跨域）；也可填写直连地址。Token 仅保存在浏览器 localStorage，并通过 WebSocket URL query 传递（务必使用 wss:// 或本地回环）。
        </p>
        <div class="dialog-actions">
          <button class="btn-ghost" @click="store.closeSettings()">取消</button>
          <button class="btn-primary" @click="save">保存</button>
        </div>
      </div>
    </div>
  </teleport>
</template>
