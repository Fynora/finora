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
    // Vitest's 5s default is too tight for the userEvent-driven tests here once several files
    // run in parallel. Register.test.tsx's "+91 prepended" case takes ~1.4s alone but exceeded
    // 5s under concurrent load on a developer machine -- and GitHub's runners are slower and
    // have fewer cores, so CI would have been intermittently red from day one. This buys
    // headroom for a slow machine without hiding a genuinely hung test: a real deadlock still
    // fails, just 15s later.
    testTimeout: 15_000,
    // Local runs only: at most 4 test workers. Vitest's default for `vitest run` is CPUs minus
    // one, 9 on a 10-core Mac, and several Claude sessions running suites at once on a 16 GB
    // machine ran it out of swap (2026-10-30). Measured on that machine (2026-10-31, full suite):
    // 9 workers peaked at 1.7-2.7 GB for the test process tree, 4 workers at 1.5-1.6 GB. Wall
    // time was too noisy to compare: other sessions held the machine's load average at 10-25.
    // CI is left alone: ci.yml picks its own worker count from the runner's CPUs and passes it on
    // the command line, which overrides this anyway. `npm test -- --maxWorkers=9` lifts the cap
    // locally.
    // CI=false counts as local.
    ...(process.env.CI && process.env.CI !== 'false' ? {} : { maxWorkers: 4 }),
  },
});
