import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { fixtureReport } from './testFixtures';

/**
 * Top merchants can hold a payment to a person: every imported row gets a merchant id, a transfer
 * to a friend included. MerchantLogo looks a logo up on Logo.dev by name, so a person's row must
 * pass `person` or the friend's name goes to Logo.dev in the image URL. The token is read once
 * when MerchantLogo loads, so it is stubbed and the panel imported fresh, as MerchantLogo.test.tsx
 * does.
 */
async function panelWithToken() {
  vi.stubEnv('VITE_LOGODEV_TOKEN', 'test-token');
  vi.resetModules();
  const { ThemeProvider: FreshTheme } = await import('../../context/ThemeContext');
  const { MerchantsPanel } = await import('./MerchantsPanel');
  return { MerchantsPanel, FreshTheme };
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('MerchantsPanel logos', () => {
  it('never sends a person\'s name to Logo.dev, and still looks up a business', async () => {
    const { MerchantsPanel, FreshTheme } = await panelWithToken();
    const base = fixtureReport();
    const report = fixtureReport({
      merchants: {
        ...base.merchants,
        rows: [
          { ...base.merchants.rows[0], merchantId: 'm1', name: 'Amazon', person: false },
          { ...base.merchants.rows[1], merchantId: 'm2', name: 'Sample Person', person: true },
        ],
      },
    });

    render(<FreshTheme><MemoryRouter><MerchantsPanel report={report} /></MemoryRouter></FreshTheme>);

    const personRow = screen.getByTestId('merchant-Sample Person');
    expect(within(personRow).queryByRole('img')).toBeNull();
    expect(personRow.querySelector('img')).toBeNull();
    expect(document.body.innerHTML).not.toContain('img.logo.dev/name/Sample');

    const businessRow = screen.getByTestId('merchant-Amazon');
    expect(businessRow.querySelector('img')?.getAttribute('src')).toContain('img.logo.dev/name/Amazon');
  });
});
