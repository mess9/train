import { defineConfig, devices } from '@playwright/test';

// Один стенд и одна база на прогон: тесты идут по очереди, сценарии — цепочкой внутри файла
export default defineConfig({
  testDir: './tests',
  globalSetup: './stand.js',
  globalTeardown: './teardown.js',
  workers: 1,
  fullyParallel: false,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    // Телефон владельца: узкий экран, касания, русская локаль
    ...devices['Pixel 7'],
    locale: 'ru-RU',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
