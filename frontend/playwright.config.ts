import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5174',
    ...devices['Desktop Chrome'],
    launchOptions: { channel: process.env.PLAYWRIGHT_CHANNEL ?? 'msedge' },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: 'node e2e/launch-backend.mjs',
      cwd: '.',
      url: 'http://127.0.0.1:8001/api/v1/health',
      timeout: 120_000,
      reuseExistingServer: false,
    },
    {
      command: 'npm run dev -- --host 127.0.0.1 --port 5174 --strictPort',
      cwd: '.',
      env: { GUANDAN_API_TARGET: 'http://127.0.0.1:8001' },
      url: 'http://127.0.0.1:5174',
      timeout: 60_000,
      reuseExistingServer: false,
    },
  ],
})
