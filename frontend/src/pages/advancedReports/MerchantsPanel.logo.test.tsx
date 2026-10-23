import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { fixtureReport } from './testFixtures';

/**
 * Top merchants can hold a friend or a small vendor under their own name: every imported row gets a
 * merchant id. Logo.dev is asked only for the row's `logoDomain` (the server's brand list), so a
 * name never reaches it. The token is read once when MerchantLogo loads, so it is stubbed and the
 * panel imported fresh, as MerchantLogo.test.tsx does.
 */
async function panelWithToken() {
  vi.stubEnv('VITE_LOGODEV_TOKEN', 'test-token');
  vi.resetModules();
  const { ThemeProvider } = await import('../../context/ThemeContext');
  const { MerchantsPanel } = await import('./MerchantsPanel');
  return { MerchantsPanel, ThemeProvider };
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('MerchantsPanel logos', () => {
  it('asks Logo.dev only by the row\'s address, and never with a name', async () => {
    const { MerchantsPanel, ThemeProvider } = await panelWithToken();
    const base = fixtureReport();
    const report = fixtureReport({
      merchants: {
        ...base.merchants,
        rows: [
          { ...base.merchants.rows[0], merchantId: 'm1', name: 'Amazon', logoDomain: 'amazon.in' },
          { ...base.merchants.rows[1], merchantId: 'm2', name: 'Sample Person', logoDomain: null },
          { ...base.merchants.rows[1], merchantId: 'm3', name: 'Sample Vendor Name', logoDomain: null },
        ],
      },
    });

    render(<ThemeProvider><MemoryRouter><MerchantsPanel report={report} /></MemoryRouter></ThemeProvider>);

    expect(screen.getByTestId('merchant-Sample Person').querySelector('img')).toBeNull();
    expect(screen.getByTestId('merchant-Sample Vendor Name').querySelector('img')).toBeNull();
    const sources = [...document.querySelectorAll('img')].map((i) => i.getAttribute('src') ?? '');
    expect(sources).toEqual([expect.stringContaining('img.logo.dev/amazon.in?')]);
    expect(document.body.innerHTML).not.toMatch(/img\.logo\.dev\/name\//);
  });
});
