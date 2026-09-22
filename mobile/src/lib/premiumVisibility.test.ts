import { PREMIUM_PLAN_VISIBLE } from './premiumVisibility';

// Premium is hidden until the owner decides to bring it back (2026-09-22). Every consumer test
// mocks this whole module, so this is the one place that reads the REAL value.
//
// jest.resetModules() + a dynamic re-import gets a fresh module instance per env value -- a static
// top-level import (the one above) would keep resolving to the instance captured at file load,
// before any test here runs. See revenueCat.test.ts for the same pattern.

describe('premiumVisibility', () => {
  const original = process.env.EXPO_PUBLIC_PREMIUM_PLAN_VISIBLE;

  afterEach(() => {
    if (original === undefined) delete process.env.EXPO_PUBLIC_PREMIUM_PLAN_VISIBLE;
    else process.env.EXPO_PUBLIC_PREMIUM_PLAN_VISIBLE = original;
  });

  async function loadWithEnv(value: string | undefined) {
    jest.resetModules();
    if (value === undefined) delete process.env.EXPO_PUBLIC_PREMIUM_PLAN_VISIBLE;
    else process.env.EXPO_PUBLIC_PREMIUM_PLAN_VISIBLE = value;
    const fresh = await import('./premiumVisibility');
    return fresh.PREMIUM_PLAN_VISIBLE;
  }

  it('has Premium hidden in the build the tests see, where the variable is not set', () => {
    // Imported statically at the top, before any mutation: the shipped default.
    expect(PREMIUM_PLAN_VISIBLE).toBe(false);
  });

  it('shows it only for the exact value "true"', async () => {
    expect(await loadWithEnv('true')).toBe(true);
  });

  it.each(['', 'false', 'TRUE', 'True', '1', 'yes', ' true'])(
    'stays hidden for %j, so a typo in the build settings cannot switch it on',
    async (value) => {
      expect(await loadWithEnv(value)).toBe(false);
    },
  );

  it('stays hidden when the variable is absent altogether', async () => {
    expect(await loadWithEnv(undefined)).toBe(false);
  });
});
