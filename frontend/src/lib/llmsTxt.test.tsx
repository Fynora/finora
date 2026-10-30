import fs from 'node:fs';
import path from 'node:path';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { KEY_PAGES, STATED_CLAIMS, llmsFullTxt, llmsTxt, planLines, siteUrl } from './llmsTxt';
import { SITE_ORIGIN } from './siteUrl';
import { PLAY_STORE_URL } from './openInApp';
import { PLANS, PRICING_CARDS } from '../pages/landing/plans';
import { faq } from '../pages/landing/landing-config';
import { HELP_ARTICLES, helpArticlePath } from '../pages/helpArticles';
import Privacy from '../pages/Privacy';
import About from '../pages/About';

const frontendRoot = path.resolve(__dirname, '../..');
const sitemap = fs.readFileSync(path.join(frontendRoot, 'public/sitemap.xml'), 'utf-8');
const sitemapUrls = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1]);

const text = llmsTxt();
const full = llmsFullTxt();

/** The visible text of a page, with whitespace collapsed the way a reader sees it. */
function pageText(Page: React.ComponentType): string {
  const { container, unmount } = render(<MemoryRouter><Page /></MemoryRouter>);
  const out = (container.textContent ?? '').replace(/\s+/g, ' ');
  unmount();
  return out;
}

describe('llms.txt format (llmstxt.org)', () => {
  it('opens with the name as the only H1, then a one-line summary in a blockquote', () => {
    const lines = text.split('\n');
    expect(lines[0]).toBe('# Fynora');
    expect(lines[1]).toBe('');
    expect(lines[2]).toMatch(/^> \S/);
    expect(text.match(/^# /gm)).toHaveLength(1);
  });

  it('writes every link as a markdown link with an absolute https address', () => {
    const links = [...text.matchAll(/\]\(([^)]+)\)/g)].map((m) => m[1]);
    expect(links.length).toBeGreaterThan(40);
    for (const link of links) expect(link, link).toMatch(/^https:\/\//);
  });
});

describe('llms.txt prices', () => {
  it('lists exactly the plans on the public pricing cards, each with the price plans.ts sells it at', () => {
    for (const plan of PRICING_CARDS) {
      const heading = planLines(plan)[0];
      expect(text).toContain(heading);
      expect(heading).toContain(`${plan.price}${plan.cadence ?? ''}`);
      if (plan.secondaryPriceNote) expect(heading).toContain(plan.secondaryPriceNote);
      for (const feature of plan.features) expect(text).toContain(`- ${feature}`);
    }
    expect(text.match(/^### /gm)).toHaveLength(PRICING_CARDS.length);
  });

  it('quotes no rupee amount the pricing cards do not sell, so a price change cannot leave a stale one', () => {
    const sold = new Set(PRICING_CARDS.flatMap((p) => [p.price, p.secondaryPriceNote]).join(' ').match(/₹[\d,]+/g));
    for (const doc of [text, full]) {
      for (const amount of doc.match(/₹[\d,]+/g) ?? []) expect(sold, amount).toContain(amount);
    }
  });

  it('does not mention Premium, which is not marketed publicly (plans.ts PRICING_CARDS)', () => {
    const premium = PLANS.find((p) => p.id === 'premium')!;
    for (const doc of [text, full]) {
      expect(doc).not.toMatch(/premium/i);
      expect(doc).not.toContain(premium.price!);
    }
  });

  it('refuses to list a plan that is not on sale', () => {
    const plus = PRICING_CARDS.find((p) => p.id === 'plus')!;
    expect(() => planLines({ ...plus, availability: 'coming-soon', price: null })).toThrow(/not on sale/);
  });
});

describe('llms.txt claims', () => {
  it('restates only what the site says: each sentence written in llmsTxt.ts is on the page it names', () => {
    const pages = { privacy: pageText(Privacy), footer: pageText(About) };
    for (const [key, claim] of Object.entries(STATED_CLAIMS)) {
      expect(pages[claim.page], `${key}: "${claim.evidence}" is no longer on the ${claim.page} page`).toContain(claim.evidence);
      expect(text, key).toContain(claim.text);
    }
  });

  it('names no bank: the public site names none (owner rule, 2026-09-21)', () => {
    const banks = /\b(HDFC|ICICI|SBI|State Bank|Axis|Kotak|HSBC|PNB|Punjab National|IDFC|Yes Bank|IndusInd|Federal Bank|Canara|Bank of Baroda|Union Bank|AU Small|RBL|Citi|Standard Chartered|American Express|Amex)\b/i;
    for (const doc of [text, full]) expect(doc).not.toMatch(banks);
  });

  it('does not describe features that were dropped or are not offered', () => {
    for (const doc of [text, full]) {
      expect(doc).not.toMatch(/gmail/i);
      expect(doc).not.toMatch(/account aggregator/i);
    }
  });
});

describe('llms.txt links', () => {
  const siteLinks = (doc: string) =>
    [...doc.matchAll(/https:\/\/app\.fynora\.net[^\s)]*/g)].map((m) => m[0]);

  it('points every app.fynora.net address at a page in the sitemap (or at llms-full.txt)', () => {
    for (const doc of [text, full]) {
      for (const link of siteLinks(doc)) {
        if (link === siteUrl('/llms-full.txt')) continue;
        expect(sitemapUrls, `${link} is not in sitemap.xml`).toContain(link);
      }
    }
  });

  it('lists the key pages and every Help answer by its own address', () => {
    for (const [, route] of KEY_PAGES) expect(text).toContain(`(${siteUrl(route)})`);
    for (const a of HELP_ARTICLES) expect(text).toContain(`- [${a.question}](${SITE_ORIGIN}${helpArticlePath(a)}): `);
  });

  it('links the Android app at the Play Store address the site uses', () => {
    expect(text).toContain(`(${PLAY_STORE_URL})`);
  });
});

describe('llms-full.txt', () => {
  it('is llms.txt followed by every Help answer and every homepage question, in full', () => {
    expect(full.startsWith(text.trimEnd())).toBe(true);
    for (const a of HELP_ARTICLES) {
      expect(full).toContain(`### ${a.question}\n\n${a.answer}\n\nSource: ${SITE_ORIGIN}${helpArticlePath(a)}`);
    }
    for (const [question, answer] of faq.items) expect(full).toContain(`### ${question}\n\n${answer}\n`);
  });
});

describe('the build', () => {
  it('writes both files to the site root', () => {
    const prerender = fs.readFileSync(path.join(frontendRoot, 'scripts/prerender.mjs'), 'utf-8');
    expect(prerender).toContain("[['llms.txt', llmsTxt()], ['llms-full.txt', llmsFullTxt()]]");
    expect(prerender).toContain('fs.writeFileSync(path.join(distDir, fileName), text);');
  });
});
