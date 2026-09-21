import { describe, it, expect, vi, afterEach } from 'vitest';
import { GMAIL_SYNC_UI_ENABLED } from './features';

// Gmail sync is paused until Google's annual CASA assessment is funded (docs/engineering/
// gmail-sync-paused.md). Some tests that touch this flag mock the module so both states can be
// exercised; these read the REAL one.

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

async function loadWithEnv(value: string | undefined): Promise<boolean> {
  vi.resetModules();
  vi.stubEnv('VITE_GMAIL_SYNC_UI_ENABLED', value); // undefined really unsets it
  const fresh = await import('./features');
  return fresh.GMAIL_SYNC_UI_ENABLED;
}

describe('features', () => {
  it('has Gmail sync paused in the build the tests see, where the variable is not set', () => {
    // Imported statically at the top, before any stubbing: the shipped default.
    expect(GMAIL_SYNC_UI_ENABLED).toBe(false);
  });

  it('shows it only for the exact value "true"', async () => {
    expect(await loadWithEnv('true')).toBe(true);
  });

  it.each(['', 'false', 'TRUE', 'True', '1', 'yes', ' true'])(
    'stays paused for %j, so a typo in the deploy settings cannot switch it on',
    async (value) => {
      expect(await loadWithEnv(value)).toBe(false);
    },
  );

  it('stays paused when the variable is absent altogether', async () => {
    expect(await loadWithEnv(undefined)).toBe(false);
  });
});
