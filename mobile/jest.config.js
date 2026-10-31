// Moved out of package.json so the worker settings below can depend on the environment.
//
// Local runs only: cap Jest at 4 workers and recycle any worker that is still holding more
// than 512 MB after a test file. Jest's default is CPUs minus one (9 on a 10-core Mac), and
// each worker's heap grows as it runs more files, because module state leaks between test
// files in the same worker. Measured on a 10-core, 16 GB M5 (2026-10-31, Node 22, full suite,
// peak memory of the whole test process tree):
//   default (9 workers)            2.8-5.1 GB over 3 runs
//   4 workers, no recycling        5.3 GB (fewer workers, each one bigger)
//   4 workers + 512 MB recycle     1.9-3.1 GB over 4 runs, and no slower at the same load
// Two Claude sessions running this suite at once pushed the machine out of swap.
//
// CI keeps its own settings, passed on the command line in .github/workflows/ci.yml
// (--workerIdleMemoryLimit=1G, Jest's default worker count). Command-line flags override this
// file, so `npm test -- --maxWorkers=8` still works locally when the machine is otherwise idle.
// CI=false counts as local, the same way Jest's own CI detection (ci-info) reads it.
const isCI = Boolean(process.env.CI) && process.env.CI !== 'false';

/** @type {import('jest').Config} */
module.exports = {
  preset: 'jest-expo',
  setupFilesAfterEnv: ['<rootDir>/src/test/setup.ts'],
  testTimeout: 15000,
  resolver: 'react-native-reanimated/jest/resolver',
  moduleNameMapper: {
    '^react-native-reanimated$': '<rootDir>/node_modules/react-native-reanimated/src/index.ts',
    '^react-native-worklets$': '<rootDir>/node_modules/react-native-worklets/src/index.ts',
  },
  ...(isCI ? {} : { maxWorkers: 4, workerIdleMemoryLimit: '512MB' }),
};
