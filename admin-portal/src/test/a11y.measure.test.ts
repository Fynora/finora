import { describe, it, expect, vi, beforeEach } from 'vitest';
import { axeViolations } from './a11y.measure';

// Regression test for the "Axe is already running" flake: a Vitest per-test timeout can abandon
// an in-flight axe.run() call without cancelling it, leaving axe-core's module-level `_running`
// flag stuck true when the next case in the same file calls axe.run() again. axeViolations must
// wait for that flag to clear rather than call straight through into axe-core's own synchronous
// assertion. See the comment on axeViolations for the full mechanism.
const { mockAxeRun, mockAxe } = vi.hoisted(() => {
  const mockAxeRun = vi.fn();
  return { mockAxeRun, mockAxe: { run: mockAxeRun, _running: false } };
});

vi.mock('axe-core', () => ({ default: mockAxe }));

describe('axeViolations', () => {
  beforeEach(() => {
    mockAxeRun.mockReset();
    mockAxe._running = false;
  });

  it('runs immediately when axe is idle', async () => {
    mockAxeRun.mockResolvedValue({ violations: [] });

    await axeViolations(document.createElement('div'));

    expect(mockAxeRun).toHaveBeenCalledTimes(1);
  });

  it('waits for a stale _running flag to clear before starting a new run', async () => {
    // Simulates a previous test's abandoned-but-still-executing axe.run() call.
    mockAxe._running = true;
    mockAxeRun.mockResolvedValue({ violations: [] });

    const pending = axeViolations(document.createElement('div'));

    // Give the polling loop a couple of ticks; axe.run must not fire while still marked running.
    await new Promise((resolve) => setTimeout(resolve, 60));
    expect(mockAxeRun).not.toHaveBeenCalled();

    // The stale run finally finishes.
    mockAxe._running = false;
    const violations = await pending;

    expect(mockAxeRun).toHaveBeenCalledTimes(1);
    expect(violations).toEqual([]);
  });
});
