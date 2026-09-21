import { defineConfig, devices } from '@playwright/test';

/**
 * Runs against an already running stack (dev servers or `docker compose --profile app up`).
 *   BASE_URL     the web app (default http://localhost:4200; the API is reached through it at /api)
 *   CARRIER_URL  the carrier simulator's admin API (default http://localhost:8091)
 */
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:4200',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
