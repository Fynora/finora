import { Text } from 'react-native';
import { render, screen } from '@testing-library/react-native';
import { MerchantLogo, logoDevUrl } from './MerchantLogo';

/**
 * Logo.dev is asked only for a web address the server chose from its own brand list
 * (MerchantBrands, sent as `logoDomain`). A merchant name, a person's name or a bank narration is
 * never part of a request: with no address there is no request at all. Mirrors web's MerchantLogo.
 */
describe('logoDevUrl', () => {
  it('builds a domain lookup URL', () => {
    expect(logoDevUrl('swiggy.com', 64, 'tok')).toBe(
      'https://img.logo.dev/swiggy.com?token=tok&size=64&format=png&fallback=404'
    );
  });

  it('returns null without a token', () => {
    expect(logoDevUrl('swiggy.com', 64, undefined)).toBeNull();
  });

  it('returns null with no address', () => {
    expect(logoDevUrl(null, 64, 'tok')).toBeNull();
    expect(logoDevUrl(undefined, 64, 'tok')).toBeNull();
    expect(logoDevUrl('  ', 64, 'tok')).toBeNull();
  });

  it('refuses anything that is not a bare domain, so a name can never be sent by mistake', () => {
    expect(logoDevUrl('Sample Person', 64, 'tok')).toBeNull();
    expect(logoDevUrl('UPI/DR/600000000002/SAMPLE SHOP/YESB', 64, 'tok')).toBeNull();
    expect(logoDevUrl('swiggy', 64, 'tok')).toBeNull();
    expect(logoDevUrl('zomato.com/name', 64, 'tok')).toBeNull();
  });
});

describe('MerchantLogo', () => {
  it('renders initials for a two-word merchant name when unconfigured (no token)', () => {
    render(<MerchantLogo merchant="Big Basket" domain="bigbasket.com" />);
    expect(screen.getByText('BB')).toBeTruthy();
  });

  it('renders the first two letters for a one-word merchant name', () => {
    render(<MerchantLogo merchant="Swiggy" />);
    expect(screen.getByText('SW')).toBeTruthy();
  });

  it('renders a caller-supplied fallback instead of initials when given one', () => {
    render(<MerchantLogo merchant="Swiggy" fallback={<Text>Custom</Text>} />);
    expect(screen.getByText('Custom')).toBeTruthy();
    expect(screen.queryByText('SW')).toBeNull();
  });
});
