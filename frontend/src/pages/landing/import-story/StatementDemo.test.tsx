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
    expect(screen.getByText('−2,450.00')).toBeInTheDocument();
    expect(screen.getByText('Salary credit')).toBeInTheDocument();
    expect(screen.getByText('Income')).toBeInTheDocument();
    expect(screen.getByText('+1,24,500.00')).toBeInTheDocument();
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
