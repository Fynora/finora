import { BANK_SYNC_CONNECT_ENABLED, GMAIL_SYNC_UI_ENABLED } from './features';

// Every other test that touches this flag mocks it, so that both states can be exercised. This is the
// one that reads the REAL shipped value: it fails the moment someone flips the constant, which is the
// point. Gmail sync is paused until Google's annual CASA assessment is funded. To switch it back on,
// follow docs/engineering/gmail-sync-paused.md, which includes updating this test.
test('Gmail sync is paused in the shipped build', () => {
  expect(GMAIL_SYNC_UI_ENABLED).toBe(false);
});

// Bank Sync's connect button reads EXPO_PUBLIC_BANK_SYNC_CONNECT_ENABLED. jest.resetModules() plus a
// dynamic re-import gives a fresh module per value; the static import above is the shipped default.
describe('BANK_SYNC_CONNECT_ENABLED', () => {
  const original = process.env.EXPO_PUBLIC_BANK_SYNC_CONNECT_ENABLED;

  afterEach(() => {
    if (original === undefined) delete process.env.EXPO_PUBLIC_BANK_SYNC_CONNECT_ENABLED;
    else process.env.EXPO_PUBLIC_BANK_SYNC_CONNECT_ENABLED = original;
  });

  async function loadWithEnv(value: string | undefined) {
    jest.resetModules();
    if (value === undefined) delete process.env.EXPO_PUBLIC_BANK_SYNC_CONNECT_ENABLED;
    else process.env.EXPO_PUBLIC_BANK_SYNC_CONNECT_ENABLED = value;
    const fresh = await import('./features');
    return fresh.BANK_SYNC_CONNECT_ENABLED;
  }

  it('is closed in the build the tests see, where the variable is not set', () => {
    expect(BANK_SYNC_CONNECT_ENABLED).toBe(false);
  });

  it('opens only for the exact value "true"', async () => {
    expect(await loadWithEnv('true')).toBe(true);
  });

  it.each(['', 'false', 'TRUE', '1', ' true', undefined])('stays closed for %j', async (value) => {
    expect(await loadWithEnv(value)).toBe(false);
  });
});
