import { render, screen, waitFor } from '@testing-library/react';
import { fireEvent } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';
import { StatementDemo } from './StatementDemo';

describe('StatementDemo', () => {
  let restoreMatchMedia: (() => void) | undefined;

  afterEach(() => {
    restoreMatchMedia?.();
    restoreMatchMedia = undefined;
  });

  it('starts idle, with a real accessible button -- nothing plays itself', () => {
    render(<StatementDemo />);
    expect(screen.getByRole('button', { name: /see it work/i })).toBeInTheDocument();
    expect(screen.queryByText(/transactions found/i)).not.toBeInTheDocument();
  });

  it('reveals real, non-hidden categorized sample transactions after a click', async () => {
    render(<StatementDemo />);
    fireEvent.click(screen.getByRole('button', { name: /see it work/i }));

    await waitFor(() => {
      expect(screen.getByText('4 transactions found')).toBeInTheDocument();
    });

    // Real content, not aria-hidden decoration.
    const resultHeading = screen.getByText('4 transactions found');
    expect(resultHeading.closest('[aria-hidden="true"]')).toBeNull();

    expect(screen.getByText('Amazon Pay')).toBeInTheDocument();
    expect(screen.getByText('Shopping')).toBeInTheDocument();
    expect(screen.getByText('−₹2,450.00')).toBeInTheDocument();
    expect(screen.getByText('Salary credit')).toBeInTheDocument();
    expect(screen.getByText('Income')).toBeInTheDocument();
    expect(screen.getByText('+₹1,24,500.00')).toBeInTheDocument();
  });

  // Same salary figure as DashboardMock's "RECENT TRANSACTIONS" a few sections down this same
  // page ("+₹1,24,500") -- a visitor scrolling past both must see one consistent format, not
  // "+1,24,500.00" here and "+₹1,24,500" there for the same number.
  it('puts the ₹ symbol on every amount, matching DashboardMock elsewhere on this page', async () => {
    render(<StatementDemo />);
    fireEvent.click(screen.getByRole('button', { name: /see it work/i }));
    await waitFor(() => screen.getByText('4 transactions found'));

    for (const amount of ['−₹2,450.00', '−₹860.00', '+₹1,24,500.00', '−₹649.00']) {
      expect(screen.getByText(amount)).toBeInTheDocument();
    }
  });

  it('skips the scan animation and shows the result immediately under prefers-reduced-motion', () => {
    restoreMatchMedia = mockMatchMedia({ '(prefers-reduced-motion: reduce)': true });
    render(<StatementDemo />);
    fireEvent.click(screen.getByRole('button', { name: /see it work/i }));

    // No intermediate "Reading transactions…" state to wait through.
    expect(screen.getByText('4 transactions found')).toBeInTheDocument();
  });

  it('lets the visitor replay the demo', async () => {
    render(<StatementDemo />);
    fireEvent.click(screen.getByRole('button', { name: /see it work/i }));
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /watch again/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /watch again/i }));
    expect(screen.getByText(/reading transactions/i)).toBeInTheDocument();
  });

  // A manual review found that each stage swap unmounts the previous stage's button, which drops
  // focus to <body> with no announcement -- confirmed by driving this in a real browser. These two
  // tests guard the fix: an aria-live region for screen readers, and real focus on "Watch again"
  // for keyboard users, instead of focus silently falling out of the page.
  it('announces the result via an aria-live region', async () => {
    render(<StatementDemo />);
    fireEvent.click(screen.getByRole('button', { name: /see it work/i }));
    await waitFor(() => screen.getByText('4 transactions found'));

    const live = screen.getByText('4 transactions found');
    expect(live.getAttribute('aria-live')).toBe('polite');
  });

  it('moves focus onto "Watch again" once the result appears, instead of losing it to unmount', async () => {
    render(<StatementDemo />);
    fireEvent.click(screen.getByRole('button', { name: /see it work/i }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /watch again/i })).toHaveFocus();
    });
  });

  // The result stage (4 rows) is taller than idle/scanning (a short placeholder). This card sits
  // in an `items-center` grid row next to the section's real copy, so letting its height change
  // measurably moved that copy on screen on every click (confirmed in a real browser: ~19px).
  // Asserting the fixed min-height is the cheapest thing jsdom (no real layout) can check here --
  // the actual "does the copy move" claim was verified with the browser tools instead.
  it('reserves height for the tallest (result) stage so shorter stages do not shrink the card', () => {
    const { container } = render(<StatementDemo />);
    const card = container.firstElementChild as HTMLElement;
    expect(card.style.minHeight).toBe('396px');
  });

  it('labels the demo as a sample and never phrases its count as a live platform metric', async () => {
    const { container } = render(<StatementDemo />);
    expect(screen.getByText(/sample statement/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /see it work/i }));
    await waitFor(() => screen.getByText('4 transactions found'));

    // Same pattern landing-claims.test.tsx guards the rest of the page with -- "N
    // transactions/statements processed/imported" reads as a live usage counter, which this must
    // not, since it is four fixed sample rows.
    expect(container.textContent).not.toMatch(/[\d,]+\+?\s*(statements?|transactions?)\s*(imported|processed)/i);
  });
});
