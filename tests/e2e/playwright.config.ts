// Emulates the Tesla browser as closely as a desktop Chromium can:
//  - Chrome for Testing 148 when CHROME_PATH is set (run `npm run install-chrome`), else bundled Chromium
//  - Tesla-like UA, 1920x1200 screen minus browser chrome, DPR 1 and 1.5 (2026.26 changed density)
//  - RFC1918 hosts fail DNS so a stray private-IP reference is caught here, not in the car
//  - plain http:// origin, so secure-context-only APIs are unavailable exactly like in the car
import { defineConfig, devices } from '@playwright/test';

const PORT = Number(process.env.FAKE_PHONE_PORT ?? 3333);
export const BASE_URL = process.env.BASE_URL ?? `http://100.99.9.9:${PORT}`;
const external = !!process.env.BASE_URL; // pointed at a real phone

const teslaUA = 'Mozilla/5.0 (X11; GNU/Linux) AppleWebKit/537.36 (KHTML, like Gecko) Chromium/148.0.7778.178 Chrome/148.0.7778.178 Safari/537.36 Tesla/2026.26';
// What the car actually sent on 2026-09-10 (Model Y, 2026.26, browser at half width): no Tesla/ token,
// 804x638 CSS px at DPR 1.96 — see docs/car-tests/model-y-2026.26.md.
const modelYUA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36';

const chromiumArgs = [
  // Map the phone address to the fake phone on localhost, and make RFC1918 unreachable.
  ...(external ? [] : [`--host-resolver-rules=MAP 100.99.9.9 127.0.0.1, MAP 192.168.* ~NOTFOUND, MAP 10.* ~NOTFOUND, MAP 172.16.* ~NOTFOUND, MAP 172.17.* ~NOTFOUND, MAP 172.31.* ~NOTFOUND`]),
  '--autoplay-policy=document-user-activation-required',
];

export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: BASE_URL,
    userAgent: teslaUA,
    viewport: { width: 1900, height: 1040 },
    deviceScaleFactor: 1,
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    launchOptions: {
      executablePath: process.env.CHROME_PATH || undefined,
      args: chromiumArgs,
    },
  },
  projects: [
    { name: 'tesla-dpr1', use: { ...devices['Desktop Chrome'], deviceScaleFactor: 1, viewport: { width: 1900, height: 1040 }, userAgent: teslaUA, launchOptions: { executablePath: process.env.CHROME_PATH || undefined, args: chromiumArgs } } },
    { name: 'tesla-dpr1.5', use: { ...devices['Desktop Chrome'], deviceScaleFactor: 1.5, viewport: { width: 1266, height: 693 }, userAgent: teslaUA, launchOptions: { executablePath: process.env.CHROME_PATH || undefined, args: chromiumArgs } } },
    { name: 'model-y-2026.26', use: { ...devices['Desktop Chrome'], deviceScaleFactor: 1.96, viewport: { width: 804, height: 638 }, userAgent: modelYUA, launchOptions: { executablePath: process.env.CHROME_PATH || undefined, args: chromiumArgs } } },
  ],
  webServer: external ? undefined : {
    command: `node ../../tools/fake-phone/server.mjs --port ${PORT} --host 127.0.0.1`,
    url: `http://127.0.0.1:${PORT}/api/status`,
    reuseExistingServer: false,
    cwd: './',
    timeout: 30_000,
  },
});
