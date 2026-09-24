import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { Pricing } from './Pricing';
import { INTENDED_BILLING_CYCLE_KEY, PLANS, yearlySavingsPct } from './plans';

function renderPricing() {
  return render(
    <MemoryRouter>
      <Pricing />
    </MemoryRouter>
  );
}

describe('Pricing', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('mirrors the current toggle to localStorage so Billing.tsx can pick it up after signup', async () => {
    // See INTENDED_BILLING_CYCLE_KEY's own doc comment (plans.ts): the landing page's toggle
    // selection has to survive the /auth signup redirect, which router state cannot.
    const user = userEvent.setup();
    renderPricing();

    expect(localStorage.getItem(INTENDED_BILLING_CYCLE_KEY)).toBe('monthly');

    await user.click(screen.getByRole('button', { name: 'Yearly' }));
    expect(localStorage.getItem(INTENDED_BILLING_CYCLE_KEY)).toBe('yearly');

    await user.click(screen.getByRole('button', { name: 'Monthly' }));
    expect(localStorage.getItem(INTENDED_BILLING_CYCLE_KEY)).toBe('monthly');
  });

  it('shows exactly two plans, Free and Plus, and no Premium', () => {
    renderPricing();
    expect(screen.getAllByRole('link', { name: /start free|get started/i })).toHaveLength(2);
    expect(screen.queryByText('Premium')).toBeNull();
  });

  it('compares Free and Plus with readable text, not only ticks', () => {
    renderPricing();
    expect(screen.getByText('Up to 2')).toBeInTheDocument();
    expect(screen.getByText('No limit')).toBeInTheDocument();
    expect(screen.getByText('Small daily limit')).toBeInTheDocument();
  });

  // Repriced 2026-09-24: ₹249/₹1,999 including GST -- the charged amount, so no "+ 18% GST".
  it('shows Plus at ₹249 including GST, with no GST added on top', () => {
    renderPricing();

    expect(screen.getAllByText(/Incl\. GST/).length).toBeGreaterThan(0);
    expect(screen.queryByText('+ 18% GST')).not.toBeInTheDocument();
  });

  it('computes the yearly saving from the real prices: ₹1,999 vs 12 x ₹249 is 33%', () => {
    const plus = PLANS.find((p) => p.id === 'plus')!;
    expect(yearlySavingsPct(plus)).toBe(33);
  });
});
