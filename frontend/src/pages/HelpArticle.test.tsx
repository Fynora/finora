import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import HelpArticle from './HelpArticle';
import Help from './Help';
import { NOT_FOUND_TITLE } from './NotFound';
import { PRICING_CARDS } from './landing/plans';
import {
  HELP_ARTICLES,
  HELP_ARTICLE_ROUTE,
  helpArticleBySlug,
  helpArticleDescription,
  helpArticleForPath,
  helpArticlePath,
  relatedHelpArticles,
} from './helpArticles';
import { HELP_CENTER_TITLE } from '../lib/structuredData';

// jsdom has no scrolling; the article route calls window.scrollTo on every slug.
let scrollTo: MockInstance<typeof window.scrollTo>;
beforeEach(() => {
  scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
});
afterEach(() => {
  vi.restoreAllMocks();
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/help" element={<Help />} />
        <Route path={HELP_ARTICLE_ROUTE} element={<HelpArticle />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('Help article slugs', () => {
  it('are unique, lower-case words joined by hyphens', () => {
    const slugs = HELP_ARTICLES.map((a) => a.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
    for (const slug of slugs) expect(slug).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
  });

  it('name no bank: the public site names none (owner rule, 2026-09-21)', () => {
    // A slug is part of a public address and of the sitemap.
    for (const a of HELP_ARTICLES) expect(a.slug).not.toMatch(/hdfc|icici|sbi|axis|kotak|hsbc|pnb|idfc|yes-bank|indusind|federal|canara|baroda/);
  });

  it('are found by slug and by address, and nothing else is', () => {
    const first = HELP_ARTICLES[0];
    expect(helpArticleBySlug(first.slug)).toBe(first);
    expect(helpArticleForPath(helpArticlePath(first))).toBe(first);
    expect(helpArticleForPath(`${helpArticlePath(first)}/`)).toBe(first);
    expect(helpArticleBySlug(undefined)).toBeUndefined();
    expect(helpArticleBySlug(first.slug.toUpperCase())).toBeUndefined();
    expect(helpArticleForPath('/help')).toBeUndefined();
    expect(helpArticleForPath(`/help/${first.slug}/more`)).toBeUndefined();
  });
});

describe('Help article content', () => {
  it('has a description for every article whose answer would not fit a search result', () => {
    for (const a of HELP_ARTICLES) {
      if (a.answer.length > 160 || a.answer.length < 70) expect(a.description, a.slug).toBeDefined();
      // A description the answer already makes redundant is a second copy to keep true.
      if (a.description) expect(a.answer.length > 160 || a.answer.length < 70, `${a.slug} needs no description`).toBe(true);
      expect(helpArticleDescription(a)).toBe(a.description ?? a.answer);
    }
  });

  it("states the Plus prices plans.ts sells, in the answer and in its description", () => {
    // The answer page and its search snippet both quote prices; they must be the prices on sale.
    const plus = PRICING_CARDS.find((p) => p.id === 'plus')!;
    const plans = helpArticleBySlug('plans-and-prices')!;
    const monthly = `${plus.price}${plus.cadence}`;
    const yearly = plus.secondaryPriceNote!.replace(/^or /, '');
    for (const text of [plans.answer, helpArticleDescription(plans)]) {
      expect(text).toContain(monthly);
      expect(text).toContain(yearly);
      expect(text).toContain('18% GST');
    }
    expect(PRICING_CARDS.map((p) => p.name)).toEqual(['Free', 'Plus']);
  });

  it('lists only real related articles, never itself, never twice, same category first', () => {
    for (const a of HELP_ARTICLES) {
      const related = relatedHelpArticles(a);
      expect(related.length, a.slug).toBeGreaterThan(0);
      expect(related.length, a.slug).toBeLessThanOrEqual(5);
      expect(related.map((r) => r.slug), a.slug).not.toContain(a.slug);
      expect(new Set(related.map((r) => r.slug)).size, a.slug).toBe(related.length);
      const sameCategory = HELP_ARTICLES.filter((r) => r.category === a.category && r !== a);
      expect(related.slice(0, sameCategory.length).map((r) => r.slug), a.slug)
        .toEqual(sameCategory.slice(0, 5).map((r) => r.slug));
    }
  });

  it('refuses a related slug that names no article', () => {
    expect(() => relatedHelpArticles({ ...HELP_ARTICLES[0], related: ['no-such-answer'] })).toThrow(/unknown related slug/);
  });
});

describe('a Help article page', () => {
  const article = helpArticleBySlug('importing-the-same-statement-twice')!;

  it('has the question as its only <h1>, and the full answer before anything else on the page', () => {
    renderAt(helpArticlePath(article));
    expect(screen.getAllByRole('heading', { level: 1 }).map((h) => h.textContent)).toEqual([article.question]);
    const main = screen.getByRole('main');
    const firstParagraph = main.querySelector('p');
    expect(firstParagraph?.textContent).toBe(article.answer);
  });

  it('links back to /help and to each related answer by its own address', () => {
    renderAt(helpArticlePath(article));
    const crumb = screen.getByRole('navigation', { name: 'Breadcrumb' });
    expect(within(crumb).getByRole('link', { name: HELP_CENTER_TITLE })).toHaveAttribute('href', '/help');
    expect(screen.getByRole('link', { name: /All Help answers/ })).toHaveAttribute('href', '/help');
    for (const r of relatedHelpArticles(article)) {
      expect(screen.getByRole('link', { name: r.question })).toHaveAttribute('href', helpArticlePath(r));
    }
  });

  it('scrolls to the top when a related answer is followed, so the new question is in view', () => {
    renderAt(helpArticlePath(article));
    scrollTo.mockClear();
    const next = relatedHelpArticles(article)[0];
    fireEvent.click(screen.getByRole('link', { name: next.question }));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(next.question);
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
  });

  it('sets the title from the question', () => {
    renderAt(helpArticlePath(article));
    expect(document.title).toBe(`${article.question} — Fynora`);
  });

  it('is the not-found page for a slug no article has', () => {
    renderAt('/help/no-such-answer');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(NOT_FOUND_TITLE);
  });
});

describe('/help', () => {
  it('is titled with the heading the article breadcrumbs name', () => {
    renderAt('/help');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(HELP_CENTER_TITLE);
  });

  it("links every question to its own page", () => {
    renderAt('/help');
    for (const a of HELP_ARTICLES) {
      expect(screen.getByRole('link', { name: a.question })).toHaveAttribute('href', helpArticlePath(a));
    }
  });
});
