import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Deliberately a separate config from vite.config.ts rather than merging it -- the dev-server
// proxy/port settings over there are meaningless for tests, and keeping this self-contained
// avoids any risk of the two configs interfering with each other.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    // Same reasoning as frontend/vitest.config.ts: Setup.test.tsx's two multi-step flows take
    // ~2s each in isolation but exceeded the 5s default under concurrent load, and GitHub's
    // runners are slower still. A real deadlock still fails, just 15s later.
    testTimeout: 15_000,
    // Local runs only: at most 4 test workers, for the reason in frontend/vitest.config.ts.
    // Measured here (2026-10-31, full suite): 9 workers peaked at 1.7 GB for the test process
    // tree, 4 workers at 0.9-1.0 GB.
    // CI=false counts as local.
    ...(process.env.CI && process.env.CI !== 'false' ? {} : { maxWorkers: 4 }),
  },
});
