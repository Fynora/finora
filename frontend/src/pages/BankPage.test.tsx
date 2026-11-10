import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { BankPageView } from './BankPage';
import { BANK_PAGES, bankPagePath } from './bankPages';
import { hero } from './landing/landing-config';

describe('BankPageView', () => {
  it("styles its sign-up button with the app's own colours, which exist on this page", () => {
    // The landing page's m-btn classes read --m-* colours defined only inside its .marketing
    // wrapper. Used here, outside it, the button had no background and white text: invisible on the
    // light theme (seen in a screenshot of the built page, 2026-11-10).
    for (const page of BANK_PAGES) {
      const view = render(<MemoryRouter initialEntries={[bankPagePath(page)]}><BankPageView page={page} /></MemoryRouter>);
      const cta = screen.getByRole('link', { name: hero.primaryCta });
      expect(cta).toHaveAttribute('href', '/auth');
      expect(cta).toHaveClass('bg-primary', 'text-on-primary');
      for (const el of view.container.querySelectorAll('[class*="m-btn"]')) {
        expect(el.closest('.marketing'), page.slug).not.toBeNull();
      }
      view.unmount();
    }
  });
});
