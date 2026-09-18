import { createLaunchUrlGuard, isClaimedPath, parseAppLink, pathIsUnder } from './appLinks';

describe('parseAppLink', () => {
  it('reads the https app link for both hosts', () => {
    expect(parseAppLink('https://app.fynora.net/verify-email?token=abc')).toEqual({
      path: '/verify-email', params: { token: 'abc' },
    });
    expect(parseAppLink('https://dev-app.fynora.net/verify-email?token=abc')).toEqual({
      path: '/verify-email', params: { token: 'abc' },
    });
  });

  it('reads the custom-scheme form as the same path', () => {
    expect(parseAppLink('finora://verify-email?token=abc')).toEqual({
      path: '/verify-email', params: { token: 'abc' },
    });
    expect(parseAppLink('finora-dev://verify-email?token=abc')).toEqual({
      path: '/verify-email', params: { token: 'abc' },
    });
  });

  it('keeps multi-segment paths on both forms', () => {
    expect(parseAppLink('https://app.fynora.net/app/imports/job-1')?.path).toBe('/app/imports/job-1');
    expect(parseAppLink('finora://app/imports/job-1')?.path).toBe('/app/imports/job-1');
  });

  it('drops a trailing slash and ignores a fragment', () => {
    expect(parseAppLink('https://app.fynora.net/app/settings/#security')?.path).toBe('/app/settings');
  });

  it('decodes percent-escapes and keeps "=" inside a value', () => {
    const link = parseAppLink('https://app.fynora.net/email-change-verify?sessionId=a%20b&token=xyz==');
    expect(link?.params).toEqual({ sessionId: 'a b', token: 'xyz==' });
  });

  it('gives an empty params object when there is no query', () => {
    expect(parseAppLink('https://app.fynora.net/verify-phone')).toEqual({ path: '/verify-phone', params: {} });
  });

  it('ignores a bare key with no "="', () => {
    expect(parseAppLink('https://app.fynora.net/register?ref')?.params).toEqual({});
  });

  it('returns null for a malformed percent-escape instead of half-reading the link', () => {
    expect(parseAppLink('https://app.fynora.net/verify-email?token=100%')).toBeNull();
  });

  it.each([
    'https://evil.example/verify-email?token=abc',
    'https://app.fynora.net.evil.example/verify-email?token=abc',
    'https://evilapp.fynora.net/verify-email?token=abc',
    'http://app.fynora.net/verify-email?token=abc',
    'otherscheme://verify-email?token=abc',
    'finora://',
    'not a url',
    '',
  ])('returns null for %p', (url) => {
    expect(parseAppLink(url)).toBeNull();
  });
});

describe('pathIsUnder', () => {
  it('matches the prefix itself and anything beneath it, but not a sibling that merely starts with it', () => {
    expect(pathIsUnder('/app/imports', '/app/imports')).toBe(true);
    expect(pathIsUnder('/app/imports/job-1', '/app/imports')).toBe(true);
    expect(pathIsUnder('/app/importsX', '/app/imports')).toBe(false);
    expect(pathIsUnder('/app', '/app/imports')).toBe(false);
  });
});

describe('isClaimedPath', () => {
  it.each(['/verify-email', '/email-change-verify', '/verify-phone', '/register', '/app/settings'])(
    'claims %s exactly',
    (p) => {
      expect(isClaimedPath(p)).toBe(true);
      expect(isClaimedPath(`${p}/deeper`)).toBe(false);
    },
  );

  it('claims /app/imports and everything under it (the link carries a job id)', () => {
    expect(isClaimedPath('/app/imports')).toBe(true);
    expect(isClaimedPath('/app/imports/job-1')).toBe(true);
  });

  it('does not claim a deeper settings page, whose target the app could not honour', () => {
    expect(isClaimedPath('/app/settings/bank-sync/abc/confirm')).toBe(false);
  });

  it('never claims /reset-password (no in-app reset flow yet) or /app/billing (the app opens it in a browser)', () => {
    expect(isClaimedPath('/reset-password')).toBe(false);
    expect(isClaimedPath('/app/billing')).toBe(false);
  });
});

describe('createLaunchUrlGuard', () => {
  it('lets a launch URL through once, then refuses it', () => {
    const isFirstDelivery = createLaunchUrlGuard();
    expect(isFirstDelivery('finora://register?ref=A')).toBe(true);
    expect(isFirstDelivery('finora://register?ref=A')).toBe(false);
    expect(isFirstDelivery('finora://register?ref=B')).toBe(true);
  });

  it('keeps each guard independent, so one hook claiming the launch URL does not starve another', () => {
    const first = createLaunchUrlGuard();
    const second = createLaunchUrlGuard();
    expect(first('finora://register?ref=A')).toBe(true);
    expect(second('finora://register?ref=A')).toBe(true);
  });
});
