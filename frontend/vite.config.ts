import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 开发代理 /api → 后端 8000，浏览器视角同源，无需 CORS。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8000', changeOrigin: true }
    }
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.ts']
  }
})
