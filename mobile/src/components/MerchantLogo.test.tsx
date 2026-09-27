import { Text } from 'react-native';
import { render, screen } from '@testing-library/react-native';
import { MerchantLogo, logoDevUrl, logoSourceFor } from './MerchantLogo';

describe('logoDevUrl', () => {
  it('builds a name/ lookup URL with the merchant encoded', () => {
    expect(logoDevUrl('Swiggy', 64, 'tok')).toBe(
      'https://img.logo.dev/name/Swiggy?token=tok&size=64&format=png&fallback=404'
    );
  });

  it('returns null without a token', () => {
    expect(logoDevUrl('Swiggy', 64, undefined)).toBeNull();
  });

  it('returns null for a blank merchant', () => {
    expect(logoDevUrl('  ', 64, 'tok')).toBeNull();
  });
});

describe('MerchantLogo', () => {
  it('renders initials for a two-word merchant name when unconfigured (no token)', () => {
    render(<MerchantLogo merchant="Big Basket" />);
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

describe('logoSourceFor', () => {
  it('never builds a Logo.dev lookup for a person, even with a token configured', () => {
    // A payment to a friend is named after the friend; a name search for a person's name returns
    // whichever company matches best. A person gets initials and no request.
    expect(logoSourceFor('Sample Person', 64, 'test-token', true)).toBeNull();
  });

  it('still builds one for a business', () => {
    expect(logoSourceFor('Swiggy', 64, 'test-token', false)).toBe(logoDevUrl('Swiggy', 64, 'test-token'));
  });
});
