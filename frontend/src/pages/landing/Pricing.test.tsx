import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { Pricing } from './Pricing';
import { INTENDED_BILLING_CYCLE_KEY } from './plans';

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
});
