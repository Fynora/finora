import { describe, expect, it } from 'vitest';
import { PLAY_STORE_URL } from './openInApp';
import {
  pageAttribution,
  playQrLinkForFile,
  playQrPath,
  playReferrer,
  playStoreLink,
} from './playStoreLink';
import { BANK_PAGES, bankPagePath } from '../pages/bankPages';
import { HELP_ARTICLES, helpArticlePath } from '../pages/helpArticles';
import { STATEMENT_CODES, statementCodePath } from '../pages/statementCodes';

describe('pageAttribution', () => {
  it('names each kind of public page by its type and slug', () => {
    expect(pageAttribution('/banks/hdfc-bank-statement')).toEqual({ pageType: 'bank_page', slug: 'hdfc-bank-statement' });
    expect(pageAttribution('/banks')).toEqual({ pageType: 'banks_hub', slug: 'banks' });
    expect(pageAttribution('/help/what-is-nach')).toEqual({ pageType: 'help_article', slug: 'what-is-nach' });
    expect(pageAttribution('/help')).toEqual({ pageType: 'help_hub', slug: 'help' });
    expect(pageAttribution('/bank-statement-codes/upi')).toEqual({ pageType: 'statement_code', slug: 'upi' });
    expect(pageAttribution('/bank-statement-codes')).toEqual({ pageType: 'statement_codes_hub', slug: 'bank-statement-codes' });
    expect(pageAttribution('/bank-statement-to-excel')).toEqual({ pageType: 'converter', slug: 'bank-statement-to-excel' });
    expect(pageAttribution('/')).toEqual({ pageType: 'home', slug: 'home' });
    expect(pageAttribution('/about')).toEqual({ pageType: 'page', slug: 'about' });
  });

  it('ignores a trailing slash and does not mistake a look-alike prefix for a section', () => {
    expect(pageAttribution('/banks/sbi-statement/')).toEqual(pageAttribution('/banks/sbi-statement'));
    expect(pageAttribution('/banks/')).toEqual(pageAttribution('/banks'));
    expect(pageAttribution('')).toEqual({ pageType: 'home', slug: 'home' });
    expect(pageAttribution('/banksy')).toEqual({ pageType: 'page', slug: 'banksy' });
    expect(pageAttribution('/helpful')).toEqual({ pageType: 'page', slug: 'helpful' });
  });

  it('passes a slug to Google only when it has the shape of a real page slug, otherwise "other"', () => {
    // The bar shows on not-found pages under the sections, so a mistyped or crafted address reaches
    // this. Nothing typed into an address that is not one of this site's slugs goes to Google.
    for (const path of [
      '/help/Odd Slug_with%20stuff', '/help/a/b', '/help/' + 'x'.repeat(61), '/help/%%%',
      '/banks/someone@example.com', '/banks/100000000001', '/banks/-lead', '/banks/trail-', '/banks/two--hyphens',
      '/banks/HDFC-bank-statement', '/foo/bar',
    ]) {
      expect(pageAttribution(path).slug, path).toBe('other');
    }
    expect(pageAttribution('/help/' + 'x'.repeat(60)).slug).toBe('x'.repeat(60));
  });

  it('keeps every real page slug exactly as it is', () => {
    for (const page of BANK_PAGES) expect(pageAttribution(bankPagePath(page)).slug).toBe(page.slug);
    for (const article of HELP_ARTICLES) expect(pageAttribution(helpArticlePath(article)).slug).toBe(article.slug);
    for (const code of STATEMENT_CODES) expect(pageAttribution(statementCodePath(code)).slug).toBe(code.slug);
  });
});

describe('playStoreLink', () => {
  it('adds the referrer in the URL-encoded form Google Play reads', () => {
    expect(playStoreLink('/banks/hdfc-bank-statement', 'badge_top')).toBe(
      'https://play.google.com/store/apps/details?id=com.fynora.android&referrer='
        + 'utm_source%3Dfynora_web%26utm_medium%3Dbank_page%26utm_campaign%3Dbank_page-hdfc-bank-statement'
        + '%26utm_content%3Dhdfc-bank-statement%26utm_term%3Dbadge_top',
    );
  });

  it('gives back the referrer tags exactly once decoded, with no stray characters', () => {
    const link = new URL(playStoreLink('/bank-statement-codes/upi', 'qr'));
    expect(link.origin + link.pathname).toBe('https://play.google.com/store/apps/details');
    expect([...link.searchParams.keys()]).toEqual(['id', 'referrer']);
    expect(link.searchParams.get('id')).toBe('com.fynora.android');
    const referrer = new URLSearchParams(link.searchParams.get('referrer')!);
    expect(Object.fromEntries(referrer)).toEqual({
      utm_source: 'fynora_web',
      utm_medium: 'statement_code',
      utm_campaign: 'statement_code-upi',
      utm_content: 'upi',
      utm_term: 'qr',
    });
    expect(link.searchParams.get('referrer')).toBe(playReferrer('/bank-statement-codes/upi', 'qr'));
  });

  it('gives every prerendered search page its own campaign', () => {
    const paths = [
      ...BANK_PAGES.map(bankPagePath),
      ...HELP_ARTICLES.map(helpArticlePath),
      ...STATEMENT_CODES.map(statementCodePath),
      '/banks', '/help', '/bank-statement-codes', '/bank-statement-to-excel', '/',
    ];
    const campaigns = paths.map((p) => new URLSearchParams(playReferrer(p, 'badge_top')).get('utm_campaign'));
    expect(new Set(campaigns).size).toBe(paths.length);
    expect(paths.length).toBe(BANK_PAGES.length + HELP_ARTICLES.length + STATEMENT_CODES.length + 5);
  });
});

describe('playQrPath and playQrLinkForFile', () => {
  it('names one file per page and reads the same link back from it', () => {
    expect(playQrPath('/banks/hdfc-bank-statement')).toBe('/qr/play-bank_page-hdfc-bank-statement.svg');
    expect(playQrPath('/')).toBe('/qr/play-home-home.svg');
    for (const path of ['/banks/hdfc-bank-statement', '/help', '/', '/bank-statement-to-excel', '/bank-statement-codes/ach-d']) {
      expect(playQrLinkForFile(playQrPath(path)), path).toBe(playStoreLink(path, 'qr'));
    }
  });

  it('reads nothing back from a path it did not make', () => {
    for (const notOurs of ['/qr/play-home.svg', '/qr/play-Bank-x.svg', '/qr/other.svg', '/qr/play-a-b.png', '/assets/x.svg', '/qr/play-a-b.svg/..']) {
      expect(playQrLinkForFile(notOurs), notOurs).toBeNull();
    }
  });

  it('leaves the plain listing for places that name the app rather than send a visitor', () => {
    expect(PLAY_STORE_URL).not.toContain('referrer');
  });
});
