import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { logoDevUrl, MerchantLogo } from './MerchantLogo';

describe('logoDevUrl', () => {
  it('builds a name/ lookup URL with size, png format, and fallback=404', () => {
    expect(logoDevUrl('Swiggy', 80, 'test-token')).toBe(
      'https://img.logo.dev/name/Swiggy?token=test-token&size=80&format=png&fallback=404'
    );
  });

  it('URL-encodes a merchant name with spaces and special characters', () => {
    expect(logoDevUrl('Big Bazaar & Co', 80, 'test-token')).toBe(
      'https://img.logo.dev/name/Big%20Bazaar%20%26%20Co?token=test-token&size=80&format=png&fallback=404'
    );
  });

  it('returns null when no token is configured', () => {
    expect(logoDevUrl('Swiggy', 80, undefined)).toBeNull();
  });

  it('returns null for a blank merchant name', () => {
    expect(logoDevUrl('   ', 80, 'test-token')).toBeNull();
  });

  it('never looks up the interest label, which names no company', () => {
    expect(logoDevUrl('interest', 80, 'test-token')).toBeNull();
    expect(logoDevUrl(' Interest ', 80, 'test-token')).toBeNull();
    expect(logoDevUrl('interest paid till 31', 80, 'test-token')).not.toBeNull();
  });
});

/**
 * The token is read once at module load (see MerchantLogo.tsx's own top-level
 * `const LOGODEV_TOKEN = import.meta.env.VITE_LOGODEV_TOKEN`) -- so these tests, which need the
 * "unconfigured" fallback stage specifically, must not merely rely on no .env file existing.
 * Someone running the suite locally with a real `frontend/.env.local` (e.g. to browser-test the
 * actual Logo.dev integration, as this component's own PR did) would otherwise flip these to the
 * `logodev` image stage and break, non-deterministically depending on what's on that developer's
 * disk. Explicitly stubbed to '' and the module freshly re-imported, the same pattern
 * BankLogo.test.tsx already uses for its own token-dependent tests.
 */
describe('MerchantLogo fallback', () => {
  async function freshMerchantLogo() {
    vi.stubEnv('VITE_LOGODEV_TOKEN', '');
    vi.resetModules();
    const mod = await import('./MerchantLogo');
    return mod.MerchantLogo;
  }

  it('renders a colored-initials badge from the merchant name when no fallback is supplied', async () => {
    const FreshMerchantLogo = await freshMerchantLogo();
    const { container } = render(<FreshMerchantLogo merchant="Swiggy Bangalore" />);
    expect(container.firstChild).not.toBeNull();
    expect(screen.getByText('SB')).toBeTruthy();
    vi.unstubAllEnvs();
  });

  it('uses a single-word merchant name\'s first two letters', async () => {
    const FreshMerchantLogo = await freshMerchantLogo();
    render(<FreshMerchantLogo merchant="Amazon" />);
    expect(screen.getByText('AM')).toBeTruthy();
    vi.unstubAllEnvs();
  });

  it('falls back to "?" for an empty merchant name rather than rendering nothing', () => {
    // No token stubbing needed -- an empty name makes logoDevUrl return null regardless of
    // whether a token is configured, so this one is deterministic either way.
    render(<MerchantLogo merchant="" />);
    expect(screen.getByText('?')).toBeTruthy();
  });

  it('renders the caller-supplied fallback instead of the default initials badge when given one', async () => {
    const FreshMerchantLogo = await freshMerchantLogo();
    render(<FreshMerchantLogo merchant="Swiggy" fallback={<span>category-icon</span>} />);
    expect(screen.getByText('category-icon')).toBeTruthy();
    vi.unstubAllEnvs();
  });

  it('always renders something identifiable, whichever stage it lands on', async () => {
    const FreshMerchantLogo = await freshMerchantLogo();
    const { container } = render(<FreshMerchantLogo merchant="Uber" />);
    expect(container.firstChild).not.toBeNull();
    expect(screen.getByTitle('Uber')).toBeTruthy();
    vi.unstubAllEnvs();
  });

  it('never looks a person up on Logo.dev, even with a token configured', async () => {
    // A payment to a friend is named after the friend. A name search for "Sample Person" returns
    // whatever company matches best, so a person gets initials and no request at all.
    vi.stubEnv('VITE_LOGODEV_TOKEN', 'test-token');
    vi.resetModules();
    const { MerchantLogo: WithToken } = await import('./MerchantLogo');
    const { container } = render(<WithToken merchant="Sample Person" person />);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('SP')).toBeTruthy();
    vi.unstubAllEnvs();
  });

  it('shows initials for an interest credit and makes no request, even with a token configured', async () => {
    vi.stubEnv('VITE_LOGODEV_TOKEN', 'test-token');
    vi.resetModules();
    const { MerchantLogo: WithToken } = await import('./MerchantLogo');
    const { container } = render(<WithToken merchant="interest" />);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('IN')).toBeTruthy();
    vi.unstubAllEnvs();
  });

  it('still looks a business up when it is not a person', async () => {
    vi.stubEnv('VITE_LOGODEV_TOKEN', 'test-token');
    vi.resetModules();
    const { MerchantLogo: WithToken } = await import('./MerchantLogo');
    const { container } = render(<WithToken merchant="Swiggy" />);
    expect(container.querySelector('img')).not.toBeNull();
    vi.unstubAllEnvs();
  });
});
