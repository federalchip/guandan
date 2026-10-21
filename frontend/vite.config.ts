import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import VueDevTools from 'vite-plugin-vue-devtools'

// 开发代理 /api → 后端 8000，浏览器视角同源，无需 CORS。
export default defineConfig(({ command, mode }) => ({
  plugins: [vue(), ...(command === 'serve' && mode === 'development' ? [VueDevTools()] : [])],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.GUANDAN_API_TARGET ?? 'http://localhost:8000', changeOrigin: true }
    }
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.{test,spec}.ts']
  }
}))
