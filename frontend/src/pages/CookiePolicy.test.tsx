import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import CookiePolicy from './CookiePolicy';

function policyText(): string {
  render(
    <MemoryRouter>
      <CookiePolicy />
    </MemoryRouter>
  );
  return document.body.textContent ?? '';
}

describe('Cookie Policy lists what loads from other companies', () => {
  // BankLogo and MerchantLogo load images from img.logo.dev in the browser by web address only (the
  // bank's own, or the server's `logoDomain` from MerchantBrands), whenever VITE_LOGODEV_TOKEN is set
  // (it is in production).
  it('names Logo.dev and what its requests carry', () => {
    const t = policyText();
    expect(t).toMatch(/bank and merchant logos\s+from Logo\.dev/i);
    expect(t).toMatch(/the request also names\s+the bank's or a well-known merchant's web address, and nothing from your\s+transactions/i);
  });
});
