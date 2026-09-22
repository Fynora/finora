import { describe, it, expect, vi, afterEach } from 'vitest';
import { PREMIUM_PLAN_VISIBLE } from './premiumVisibility';

// Premium is hidden until the owner decides to bring it back (2026-09-22). Every consumer test
// mocks this whole module, so this is the one place that reads the REAL value.

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

async function loadWithEnv(value: string | undefined): Promise<boolean> {
  vi.resetModules();
  vi.stubEnv('VITE_PREMIUM_PLAN_VISIBLE', value); // undefined really unsets it
  const fresh = await import('./premiumVisibility');
  return fresh.PREMIUM_PLAN_VISIBLE;
}

describe('premiumVisibility', () => {
  it('has Premium hidden in the build the tests see, where the variable is not set', () => {
    // Imported statically at the top, before any stubbing: the shipped default.
    expect(PREMIUM_PLAN_VISIBLE).toBe(false);
  });

  it('shows it only for the exact value "true"', async () => {
    expect(await loadWithEnv('true')).toBe(true);
  });

  it.each(['', 'false', 'TRUE', 'True', '1', 'yes', ' true'])(
    'stays hidden for %j, so a typo in the deploy settings cannot switch it on',
    async (value) => {
      expect(await loadWithEnv(value)).toBe(false);
    },
  );

  it('stays hidden when the variable is absent altogether', async () => {
    expect(await loadWithEnv(undefined)).toBe(false);
  });
});
