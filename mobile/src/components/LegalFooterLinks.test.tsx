import { Linking } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { LegalFooterLinks } from './LegalFooterLinks';
import { ThemeProvider } from '../theme';
import { webUrl } from '../lib/webUrl';

function renderComponent() {
  return render(
    <ThemeProvider>
      <LegalFooterLinks />
    </ThemeProvider>
  );
}

describe('LegalFooterLinks', () => {
  it('shows both links', () => {
    renderComponent();

    expect(screen.getByText('Privacy Policy')).toBeTruthy();
    expect(screen.getByText('Terms of Service')).toBeTruthy();
  });

  it('opens the web Privacy page when pressed', () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderComponent();

    fireEvent.press(screen.getByText('Privacy Policy'));

    expect(openURL).toHaveBeenCalledWith(webUrl('/privacy'));
  });

  it('opens the web Terms page when pressed', () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderComponent();

    fireEvent.press(screen.getByText('Terms of Service'));

    expect(openURL).toHaveBeenCalledWith(webUrl('/terms'));
  });
});
