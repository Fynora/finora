import { describe, expect, it } from 'vitest';
import { inviteMessage, invitePlayLink, inviteSignupLink } from './inviteLink';

describe('inviteLink', () => {
  it('builds the sign-up link with the referral code and the campaign tags', () => {
    const url = new URL(inviteSignupLink('https://app.fynora.net/', 'AB12CD34'));
    expect(url.origin + url.pathname).toBe('https://app.fynora.net/register');
    expect(url.searchParams.get('ref')).toBe('AB12CD34');
    expect(url.searchParams.get('utm_source')).toBe('friend_invite');
    expect(url.searchParams.get('utm_medium')).toBe('referral');
    expect(url.searchParams.get('utm_campaign')).toBe('after_first_import');
  });

  it('encodes a code that is not URL-safe rather than breaking the link', () => {
    const url = new URL(inviteSignupLink('https://app.fynora.net', 'A&B=C'));
    expect(url.searchParams.get('ref')).toBe('A&B=C');
    expect(url.searchParams.get('utm_source')).toBe('friend_invite');
  });

  it('gives the Play link the tags as one encoded install referrer, without the code', () => {
    const url = new URL(invitePlayLink());
    expect(url.origin + url.pathname).toBe('https://play.google.com/store/apps/details');
    expect(url.searchParams.get('id')).toBe('com.fynora.android');
    expect(invitePlayLink()).toContain('referrer=utm_source%3Dfriend_invite%26utm_medium%3Dreferral%26utm_campaign%3Dafter_first_import');
    const referrer = new URLSearchParams(url.searchParams.get('referrer')!);
    expect(Object.fromEntries(referrer)).toEqual({
      utm_source: 'friend_invite', utm_medium: 'referral', utm_campaign: 'after_first_import',
    });
  });

  it('puts both links and the code in the message', () => {
    const message = inviteMessage('https://app.fynora.net', 'AB12CD34');
    expect(message).toContain(inviteSignupLink('https://app.fynora.net', 'AB12CD34'));
    expect(message).toContain(invitePlayLink());
    expect(message).toContain('My referral code: AB12CD34');
    // No reward claim in what the friend receives.
    expect(message).not.toMatch(/free|reward|earn/i);
  });
});
