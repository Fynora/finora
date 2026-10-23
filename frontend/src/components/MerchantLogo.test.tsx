import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { logoDevUrl, MerchantLogo } from './MerchantLogo';

afterEach(() => {
  vi.unstubAllEnvs();
});

/**
 * Logo.dev is asked only for a web address the server chose from its own brand list
 * (MerchantBrands, sent as `logoDomain`). A merchant name, a person's name or a bank narration is
 * never part of a request: with no address there is no request at all.
 */
describe('logoDevUrl', () => {
  it('builds a domain lookup URL with size, png format, and fallback=404', () => {
    expect(logoDevUrl('swiggy.com', 80, 'test-token')).toBe(
      'https://img.logo.dev/swiggy.com?token=test-token&size=80&format=png&fallback=404'
    );
  });

  it('returns null when no token is configured', () => {
    expect(logoDevUrl('swiggy.com', 80, undefined)).toBeNull();
  });

  it('returns null with no address', () => {
    expect(logoDevUrl(null, 80, 'test-token')).toBeNull();
    expect(logoDevUrl(undefined, 80, 'test-token')).toBeNull();
    expect(logoDevUrl('   ', 80, 'test-token')).toBeNull();
  });

  it('refuses anything that is not a bare domain, so a name can never be sent by mistake', () => {
    expect(logoDevUrl('Sample Person', 80, 'test-token')).toBeNull();
    expect(logoDevUrl('UPI/DR/600000000002/SAMPLE SHOP/YESB', 80, 'test-token')).toBeNull();
    expect(logoDevUrl('swiggy', 80, 'test-token')).toBeNull();
    expect(logoDevUrl('zomato.com/name', 80, 'test-token')).toBeNull();
  });
});

/**
 * The token is read once at module load (`const LOGODEV_TOKEN = import.meta.env.VITE_LOGODEV_TOKEN`),
 * so each test stubs it and imports the module fresh, the pattern BankLogo.test.tsx uses.
 */
async function logoWithToken(token: string) {
  vi.stubEnv('VITE_LOGODEV_TOKEN', token);
  vi.resetModules();
  return (await import('./MerchantLogo')).MerchantLogo;
}

describe('MerchantLogo', () => {
  it('requests the logo by the given address only, never by the name shown', async () => {
    const WithToken = await logoWithToken('test-token');
    const { container } = render(<WithToken merchant="Swiggy Instamart" domain="swiggy.com" />);
    const src = container.querySelector('img')?.getAttribute('src') ?? '';
    expect(src).toContain('img.logo.dev/swiggy.com?');
    expect(src).not.toMatch(/instamart|name\//i);
  });

  it('makes no request without an address, even for a name that looks like a brand', async () => {
    const WithToken = await logoWithToken('test-token');
    const { container } = render(<WithToken merchant="Swiggy" domain={null} />);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('SW')).toBeTruthy();
  });

  it('shows initials for a person, with no request', async () => {
    const WithToken = await logoWithToken('test-token');
    const { container } = render(<WithToken merchant="Sample Person" />);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('SP')).toBeTruthy();
  });

  it('renders a colored-initials badge when unconfigured', async () => {
    const NoToken = await logoWithToken('');
    render(<NoToken merchant="Swiggy Bangalore" domain="swiggy.com" />);
    expect(screen.getByText('SB')).toBeTruthy();
  });

  it('falls back to "?" for an empty name rather than rendering nothing', async () => {
    const NoToken = await logoWithToken('');
    render(<NoToken merchant="" />);
    expect(screen.getByText('?')).toBeTruthy();
  });

  it('renders the caller-supplied fallback instead of the default initials badge when given one', async () => {
    const NoToken = await logoWithToken('');
    render(<NoToken merchant="Swiggy" fallback={<span>category-icon</span>} />);
    expect(screen.getByText('category-icon')).toBeTruthy();
  });

  it('always renders something identifiable', async () => {
    const NoToken = await logoWithToken('');
    render(<NoToken merchant="Uber" />);
    expect(screen.getByTitle('Uber')).toBeTruthy();
  });
});
