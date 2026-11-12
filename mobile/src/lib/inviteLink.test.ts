import { inviteAppStoreLink, inviteMessage, invitePlayLink, inviteSignupLink } from './inviteLink';

describe('inviteLink', () => {
  it('builds the web sign-up link with the referral code and the campaign tags', () => {
    const url = new URL(inviteSignupLink('AB12CD34'));
    expect(url.origin + url.pathname).toBe('https://app.fynora.net/register');
    expect(url.searchParams.get('ref')).toBe('AB12CD34');
    expect(url.searchParams.get('utm_source')).toBe('friend_invite');
    expect(url.searchParams.get('utm_medium')).toBe('referral');
    expect(url.searchParams.get('utm_campaign')).toBe('after_import');
  });

  it('is the same link the web app shares (frontend/src/lib/inviteLink.ts)', () => {
    expect(inviteSignupLink('AB12CD34')).toBe(
      'https://app.fynora.net/register?ref=AB12CD34&utm_source=friend_invite&utm_medium=referral&utm_campaign=after_import',
    );
  });

  it('encodes a code that is not URL-safe', () => {
    expect(new URL(inviteSignupLink('A&B')).searchParams.get('ref')).toBe('A&B');
  });

  it('gives the Play link the tags as one encoded install referrer, without the code', () => {
    expect(invitePlayLink()).toBe(
      'https://play.google.com/store/apps/details?id=com.fynora.android'
        + '&referrer=utm_source%3Dfriend_invite%26utm_medium%3Dreferral%26utm_campaign%3Dafter_import',
    );
  });

  it('links the iPhone app at the same App Store listing the website links', () => {
    expect(inviteAppStoreLink()).toBe('https://apps.apple.com/in/app/fynora-personal-finance/id6809816093'); // synthetic-ok: the public App Store listing URL, not customer data
  });

  it('puts the sign-up link, both app links and the code in the message, and no reward claim', () => {
    const message = inviteMessage('AB12CD34');
    expect(message).toContain(inviteSignupLink('AB12CD34'));
    expect(message).toContain(invitePlayLink());
    expect(message).toContain(`iPhone app: ${inviteAppStoreLink()}`);
    expect(message).toContain('My referral code: AB12CD34');
    expect(message).not.toMatch(/free|reward|earn/i);
  });
});
