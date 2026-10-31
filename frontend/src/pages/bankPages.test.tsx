import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter, StaticRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import Banks from './Banks';
import { BankPageView, longDate } from './BankPage';
import {
  BANK_PAGES,
  BANKS_HUB_TITLE,
  FREE_PLAN_LIMIT,
  bankPageBySlug,
  bankPageFaq,
  bankPageForPath,
  bankPagePath,
  notAffiliatedLine,
} from './bankPages';
import { PLANS } from './landing/plans';
import { structuredDataFor } from '../lib/structuredData';

const markup = (page: (typeof BANK_PAGES)[number]) =>
  renderToStaticMarkup(
    <StaticRouter location={bankPagePath(page)}>
      <BankPageView page={page} />
    </StaticRouter>,
  );

/** Every string a page record carries, for the wording rules below. */
const textOf = (page: (typeof BANK_PAGES)[number]) =>
  JSON.stringify(page) + ' ' + bankPageFaq(page).flat().join(' ');

const decode = (html: string) => {
  const el = document.createElement('textarea');
  el.innerHTML = html.replace(/<[^>]+>/g, ' ');
  return el.value.replace(/\s+/g, ' ');
};

describe('bank statement pages: the data', () => {
  it('has one page per bank and product, each at a stable lower-case slug, never twice', () => {
    expect(BANK_PAGES.length).toBe(12);
    const slugs = BANK_PAGES.map((p) => p.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
    for (const slug of slugs) expect(slug).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
    for (const page of BANK_PAGES) {
      expect(bankPageBySlug(page.slug)).toBe(page);
      expect(bankPageForPath(bankPagePath(page))).toBe(page);
      expect(bankPageForPath(`${bankPagePath(page)}/`)).toBe(page);
    }
    expect(bankPageBySlug('no-such-bank')).toBeUndefined();
    expect(bankPageForPath('/banks')).toBeUndefined();
  });

  it('does not have an SBI Card page yet: its answer key waits for the owner to countersign it', () => {
    // Remove this test in the same change that adds the SBI Card page, once the key is countersigned.
    expect(BANK_PAGES.map((p) => p.slug)).not.toContain('sbi-card-statement');
  });

  it('gives every page download steps or says why there are none, and names its sources with the day read', () => {
    for (const page of BANK_PAGES) {
      const stepCount = page.steps.reduce((n, g) => n + g.steps.length, 0);
      expect(stepCount > 0 || Boolean(page.noStepsNote), page.slug).toBe(true);
      expect(page.sources.length, page.slug).toBeGreaterThan(0);
      for (const source of page.sources) {
        expect(source.url, page.slug).toMatch(/^https:\/\//);
        // The bank's own site: its bank.in domain (RBI's bank domain), or SBI Card's own.
        const host = new URL(source.url).hostname;
        expect(host.endsWith('.bank.in') || host === 'www.sbicard.com', `${page.slug}: ${host}`).toBe(true);
        expect(() => longDate(source.checked), page.slug).not.toThrow();
      }
      expect(() => longDate(page.measuredOn), page.slug).not.toThrow();
    }
  });

  it('says what Fynora reads, and a card page says it reads the total amount due', () => {
    for (const page of BANK_PAGES) {
      expect(page.reads.length, page.slug).toBeGreaterThan(0);
      if (page.product === 'credit-card') expect(page.reads.join(' '), page.slug).toMatch(/total amount due/i);
      else expect(page.reads.join(' '), page.slug).toMatch(/opening and closing balance/i);
    }
  });
});

describe('bank statement pages: what they may and may not say', () => {
  it('never prints a bank password rule (owner decision, 2026-10-31)', () => {
    const rule = /date of birth|\bDOB\b|\bDDMM\b|customer ?id|cust ?id|first (four|4) letters|last (four|4) digits|\bCRN\b|password is your|password will be/i;
    for (const page of BANK_PAGES) {
      expect(textOf(page), page.slug).not.toMatch(rule);
      expect(decode(markup(page)), page.slug).not.toMatch(rule);
    }
  });

  it('promises checking, never perfection', () => {
    const overclaim = /perfect|flawless|guarantee|100 ?%|always (reads|works)|every (bank|layout)|official partner|in partnership/i;
    for (const page of BANK_PAGES) expect(textOf(page), page.slug).not.toMatch(overclaim);
  });

  it('claims no scanned-PDF reading and no Excel reading', () => {
    for (const page of BANK_PAGES) {
      const text = decode(markup(page));
      expect(text, page.slug).not.toMatch(/scann|photograph|OCR/i);
      expect(text, page.slug).not.toMatch(/reads? (Excel|XLS)/i);
    }
  });

  it("shows the not-affiliated line with the bank's name on every page", () => {
    for (const page of BANK_PAGES) {
      const text = decode(markup(page));
      expect(text, page.slug).toContain(notAffiliatedLine(page.bankName));
      expect(notAffiliatedLine(page.bankName)).toMatch(/not affiliated with, endorsed by or partnered with/);
    }
  });

  it("states the Free plan's limit and Plus's price from plans.ts, not typed a second time", () => {
    const plus = PLANS.find((p) => p.id === 'plus')!;
    expect(FREE_PLAN_LIMIT).toMatch(/one month per statement/i);
    for (const page of BANK_PAGES) {
      const text = decode(markup(page));
      expect(text.toLowerCase(), page.slug).toContain(FREE_PLAN_LIMIT!.toLowerCase());
      expect(bankPageFaq(page).flat().join(' '), page.slug).toContain(plus.price!);
    }
  });
});

describe('bank statement pages: the page itself', () => {
  it('leads with the direct answer, under the title as the only <h1>', () => {
    for (const page of BANK_PAGES) {
      const html = markup(page);
      const h1s = html.match(/<h1[^>]*>([^<]*)<\/h1>/g) ?? [];
      expect(h1s, page.slug).toHaveLength(1);
      expect(h1s[0], page.slug).toContain(page.title);
      expect(html.indexOf(page.answer.slice(0, 40)), page.slug).toBeGreaterThan(html.indexOf(page.title));
      expect(html.indexOf(page.answer.slice(0, 40)), page.slug).toBeLessThan(html.indexOf('<h2'));
    }
  });

  it('marks up exactly the FAQ it shows, under a Home > Bank statements > page trail', () => {
    for (const page of BANK_PAGES) {
      const blocks = structuredDataFor(bankPagePath(page), page.title) as Array<Record<string, unknown>>;
      const faq = blocks.find((b) => b['@type'] === 'FAQPage') as { mainEntity: Array<{ name: string; acceptedAnswer: { text: string } }> };
      expect(faq.mainEntity.map((q) => [q.name, q.acceptedAnswer.text]), page.slug).toEqual(bankPageFaq(page));
      const text = decode(markup(page));
      for (const [question, answer] of bankPageFaq(page)) {
        expect(text, page.slug).toContain(question);
        expect(text, page.slug).toContain(answer);
      }
      const trail = blocks.find((b) => b['@type'] === 'BreadcrumbList') as { itemListElement: Array<{ name: string }> };
      expect(trail.itemListElement.map((i) => i.name), page.slug).toEqual(['Home', BANKS_HUB_TITLE, page.title]);
    }
  });

  it('refuses structured data for an address under /banks/ that is not a page', () => {
    expect(() => structuredDataFor('/banks/no-such-bank', 'X')).toThrow(/no bank page/);
    expect(() => structuredDataFor(bankPagePath(BANK_PAGES[0]), 'Some other heading')).toThrow(/heading/);
  });

  it('links every source and dates it in words, the same in the browser and the HTML', () => {
    expect(longDate('2026-10-31')).toBe('31 October 2026');
    expect(longDate('2026-01-05')).toBe('5 January 2026');
    expect(() => longDate('31/10/2026')).toThrow();
    for (const page of BANK_PAGES) {
      const html = markup(page);
      for (const source of page.sources) {
        expect(html, page.slug).toContain(`href="${source.url}"`);
        expect(decode(html), page.slug).toContain(`(read ${longDate(source.checked)})`);
      }
    }
  });
});

describe('/banks', () => {
  it('links every bank page, savings and cards under their own headings', () => {
    render(<MemoryRouter><Banks /></MemoryRouter>);
    expect(screen.getByRole('heading', { level: 1, name: BANKS_HUB_TITLE })).toBeInTheDocument();
    for (const page of BANK_PAGES) {
      expect(screen.getByRole('link', { name: page.title })).toHaveAttribute('href', bankPagePath(page));
    }
    expect(screen.getByRole('heading', { name: 'Bank account statements' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Credit card statements' })).toBeInTheDocument();
    expect(screen.getByText(/not affiliated with, endorsed by or partnered with any bank named here/)).toBeInTheDocument();
  });
});
