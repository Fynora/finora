import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { RecurringItem } from '../../api/endpoints';
import { ComingUpCard, PaidRegularlyCard } from './RegularPaymentsCards';

// Every name and amount here is invented.
function recurring(overrides: Partial<RecurringItem> = {}): RecurringItem {
  return {
    merchant: 'sample gym', label: 'Monthly', averageAmount: 1500, occurrences: 3, lastDate: '2026-09-05',
    nextEstimate: '2026-10-05', state: 'NONE', ...overrides,
  };
}

describe('ComingUpCard', () => {
  it('leads with what is still to go out, and lists payments soonest first', () => {
    render(
      <ComingUpCard
        rows={[
          { item: recurring({ merchant: 'sample owner', averageAmount: 10000, nextEstimate: '2026-10-20' }), past: false },
          { item: recurring({ merchant: 'netflix', averageAmount: 649, nextEstimate: '2026-10-25' }), past: false },
        ]}
        stillToGoOut={10649} latestStatementEnd="2026-09-30" status="ready"
      />,
    );
    expect(screen.getByText('Still to go out this month')).toBeInTheDocument();
    expect(screen.getByText('₹10,649')).toBeInTheDocument();
    const items = screen.getAllByRole('listitem');
    expect(items.map((li) => li.getAttribute('aria-label'))).toEqual([
      'sample owner, ₹10,000, around 20 Oct', 'netflix, ₹649, around 25 Oct',
    ]);
  });

  it('never shows a past date as ahead: "expected around", and the note on which statement settles it', () => {
    render(
      <ComingUpCard
        rows={[{ item: recurring({ merchant: 'sample owner', averageAmount: 10000, nextEstimate: '2026-10-01' }), past: true }]}
        stillToGoOut={0} latestStatementEnd="2026-09-30" status="ready"
      />,
    );
    expect(screen.getByRole('listitem', { name: 'sample owner, ₹10,000, expected around 1 Oct, may already be paid' })).toBeInTheDocument();
    expect(screen.getByText("Your latest statement ends 30 Sep. Upload October's to see if these were paid.")).toBeInTheDocument();
  });

  it('shows nothing with nothing coming up', () => {
    const { container } = render(<ComingUpCard rows={[]} stillToGoOut={0} latestStatementEnd={null} status="ready" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('says so when repeating payments failed to load', () => {
    render(<ComingUpCard rows={[]} stillToGoOut={0} latestStatementEnd={null} status="error" />);
    expect(screen.getByText("Couldn't load your repeating payments.")).toBeInTheDocument();
  });
});

describe('PaidRegularlyCard', () => {
  const rows = [
    { item: recurring({ merchant: 'sample owner', averageAmount: 36000, yourShare: 9000, sharedWith: 3 }), monthly: 9000, rising: false },
    { item: recurring({ merchant: 'netflix', averageAmount: 499, latestAmount: 649, occurrences: 4 }), monthly: 499, rising: true },
  ];

  function renderCard(overrides: Partial<Parameters<typeof PaidRegularlyCard>[0]> = {}) {
    const onDismiss = vi.fn();
    const onConfirm = vi.fn();
    render(
      <PaidRegularlyCard
        rows={rows} monthlyTotal={9499} yearlyTotal={113988} status="ready" confirmed={new Set()}
        onDismiss={onDismiss} onConfirm={onConfirm} {...overrides}
      />,
    );
    return { onDismiss, onConfirm };
  }

  it('leads with the yearly total, the monthly total and the count under it', () => {
    renderCard();
    expect(screen.getByText('₹1,13,988')).toBeInTheDocument();
    expect(screen.getByText('a year')).toBeInTheDocument();
    expect(screen.getByText('₹9,499 a month across 2 payments')).toBeInTheDocument();
  });

  it("shows a shared bill at the user's share, and marks a payment that went up", () => {
    renderCard();
    const owner = screen.getByRole('listitem', { name: /^sample owner/ });
    expect(within(owner).getByText('₹9,000')).toBeInTheDocument();
    expect(within(owner).getByText('Monthly · your share of ₹36,000, split with 3')).toBeInTheDocument();
    const tv = screen.getByRole('listitem', { name: 'netflix, Monthly, ₹499, latest payment went up' });
    expect(within(tv).getByText('Monthly · seen 4×')).toBeInTheDocument();
  });

  it('keeps "Yes, keep tracking this" and "Not recurring"', async () => {
    const { onDismiss, onConfirm } = renderCard();
    await userEvent.click(screen.getByRole('button', { name: 'Confirm netflix as recurring' }));
    expect(onConfirm).toHaveBeenCalledWith('netflix');
    await userEvent.click(screen.getByRole('button', { name: 'Not recurring: dismiss netflix' }));
    expect(onDismiss).toHaveBeenCalledWith('netflix');
  });

  it('marks a confirmed payment as confirmed', () => {
    renderCard({ confirmed: new Set(['netflix']) });
    expect(screen.getByRole('button', { name: 'netflix confirmed as recurring' })).toBeDisabled();
  });

  it('explains what it needs when nothing repeats yet', () => {
    renderCard({ rows: [], monthlyTotal: 0, yearlyTotal: 0 });
    expect(screen.getByText('No repeating payments found yet')).toBeInTheDocument();
  });

  it('announces that it is loading, before any shape appears', () => {
    renderCard({ rows: [], status: 'loading' });
    expect(screen.getByRole('status')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Loading repeating payments')).toBeInTheDocument();
  });

  it('says so when repeating payments failed to load', () => {
    renderCard({ rows: [], status: 'error' });
    expect(screen.getByText("Couldn't load your repeating payments.")).toBeInTheDocument();
  });
});
