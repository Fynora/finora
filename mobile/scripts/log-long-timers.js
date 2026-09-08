// Temporary CI diagnostic (not part of the app): logs the creation stack trace of any setTimeout
// scheduled with a delay >= 30s. A Node diagnostic report captured mid-hang on the mobile CI job
// showed one referenced, one-shot libuv timer with ~132s left to fire -- exactly matching the
// observed post-test-completion gap -- but the report gives no creation stack for handles. Loaded
// via NODE_OPTIONS="--require" so it patches the global before jest's own code (and any worker's,
// since NODE_OPTIONS is inherited by forked children) ever runs, in whichever process actually
// schedules it. Delete this file and its NODE_OPTIONS reference in ci.yml once the source is found.
const originalSetTimeout = global.setTimeout;
global.setTimeout = function (fn, delay, ...args) {
  if (typeof delay === 'number' && delay >= 30000) {
    console.error(`[TIMER-DIAG] setTimeout(${delay}ms) scheduled at ${new Date().toISOString()}:\n${new Error().stack}`);
  }
  return originalSetTimeout(fn, delay, ...args);
};
