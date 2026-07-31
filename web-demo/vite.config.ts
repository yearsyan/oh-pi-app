import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    // 反代 WebSocket：浏览器只需连同源的 ws://localhost:5173/ws，
    // 避免跨域与 Origin 校验问题（保留原始 Host，网关同源校验可通过）。
    proxy: {
      '/ws': {
        target: 'http://127.0.0.1:8080',
        ws: true,
      },
    },
  },
})
