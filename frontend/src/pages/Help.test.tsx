import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import Help from './Help';
import { HELP_ARTICLES, HELP_CATEGORIES } from './helpArticles';

describe('Help', () => {
  it('has no Gmail Sync category or answers (dropped for v1, owner decision 2026-09-21)', () => {
    // Help said Gmail Sync was "Premium only" while the landing page listed it under Plus. The feature
    // is no longer offered, so neither surface may describe it.
    render(
      <MemoryRouter>
        <Help />
      </MemoryRouter>
    );
    expect(document.body.textContent ?? '').not.toMatch(/gmail/i);
  });

  it('does not say Premium -- it is hidden from the UI (owner decision, 2026-09-22)', () => {
    render(
      <MemoryRouter>
        <Help />
      </MemoryRouter>
    );
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/premium/i);
    expect(text).toMatch(/Two: Free and Plus/i);
  });

  it('puts every category under its own <h2>, every article under an <h3>, in article order', () => {
    render(
      <MemoryRouter>
        <Help />
      </MemoryRouter>
    );
    expect(screen.getAllByRole('heading', { level: 1 }).map((h) => h.textContent)).toEqual(['Help Center']);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(HELP_CATEGORIES);
    expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(HELP_ARTICLES.map((a) => a.question));
  });

  it('keeps the search and the category filter working over the grouped list', () => {
    render(
      <MemoryRouter>
        <Help />
      </MemoryRouter>
    );
    fireEvent.click(screen.getByRole('button', { name: 'Budgets' }));
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Budgets']);
    expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(HELP_ARTICLES.filter((a) => a.category === 'Budgets').length);

    fireEvent.click(screen.getByRole('button', { name: 'All Topics' }));
    fireEvent.change(screen.getByPlaceholderText(/Search for help/), { target: { value: 'duplicate' } });
    const shown = screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
    expect(shown.length).toBeGreaterThan(0);
    for (const q of shown) {
      const article = HELP_ARTICLES.find((a) => a.question === q)!;
      expect(`${article.question} ${article.answer} ${article.category}`.toLowerCase()).toContain('duplicate');
    }

    fireEvent.change(screen.getByPlaceholderText(/Search for help/), { target: { value: 'zzzz-no-such-thing' } });
    expect(screen.queryAllByRole('heading', { level: 2 })).toHaveLength(0);
    expect(screen.getByText(/No articles match/)).toBeInTheDocument();
  });
});
