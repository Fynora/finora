import { isSafeExternalUrl } from './safeUrl';

/**
 * Bug fix / security hardening: SettingsBankSyncScreen handed the account-aggregator redirectUrl
 * straight to Linking.openURL() with no scheme check at all. The value comes from Finora's own
 * backend today, so this pins defense-in-depth, not a demonstrated live exploit -- see safeUrl.ts's
 * own doc comment, and admin-portal/src/lib/safeUrl.test.ts for the equivalent web-side guard this
 * mirrors.
 */
describe('isSafeExternalUrl', () => {
  it('accepts a normal https URL', () => {
    expect(isSafeExternalUrl('https://ecollect.setu.co/abc')).toBe(true);
  });

  it('accepts a normal http URL', () => {
    expect(isSafeExternalUrl('http://example.com')).toBe(true);
  });

  it('rejects a javascript: URL', () => {
    expect(isSafeExternalUrl('javascript:alert(1)')).toBe(false);
  });

  it('rejects an intent: URL (the Android Intent Scheme attack class)', () => {
    expect(isSafeExternalUrl('intent://evil#Intent;scheme=http;package=com.evil;end')).toBe(false);
  });

  it('rejects a data: URL', () => {
    expect(isSafeExternalUrl('data:text/html,<script>alert(1)</script>')).toBe(false);
  });

  it('rejects a scheme-relative URL (no explicit http/https)', () => {
    expect(isSafeExternalUrl('//evil.example.com')).toBe(false);
  });

  it('rejects null, undefined, and the empty string', () => {
    expect(isSafeExternalUrl(null)).toBe(false);
    expect(isSafeExternalUrl(undefined)).toBe(false);
    expect(isSafeExternalUrl('')).toBe(false);
  });
});
