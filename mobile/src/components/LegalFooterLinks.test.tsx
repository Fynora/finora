import { Linking } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { LegalFooterLinks } from './LegalFooterLinks';
import { ThemeProvider } from '../theme';
import { webUrl } from '../lib/webUrl';

function renderComponent(hideConsentLinks = false) {
  return render(
    <ThemeProvider>
      <LegalFooterLinks hideConsentLinks={hideConsentLinks} />
    </ThemeProvider>
  );
}

describe('LegalFooterLinks', () => {
  it('shows all four links, matching SettingsScreen Legal section', () => {
    renderComponent();

    expect(screen.getByText('Privacy Policy')).toBeTruthy();
    expect(screen.getByText('Terms of Service')).toBeTruthy();
    expect(screen.getByText('Trust & Security')).toBeTruthy();
    expect(screen.getByText('Data Portability Promise')).toBeTruthy();
  });

  it('drops Privacy and Terms when the consent notice above already links them, keeping the other two', () => {
    renderComponent(true);

    expect(screen.queryByText('Privacy Policy')).toBeNull();
    expect(screen.queryByText('Terms of Service')).toBeNull();
    expect(screen.getByText('Trust & Security')).toBeTruthy();
    expect(screen.getByText('Data Portability Promise')).toBeTruthy();
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

  it('opens the web Trust & Security page when pressed', () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderComponent();

    fireEvent.press(screen.getByText('Trust & Security'));

    expect(openURL).toHaveBeenCalledWith(webUrl('/trust'));
  });

  it('opens the web Data Portability Promise page when pressed', () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderComponent();

    fireEvent.press(screen.getByText('Data Portability Promise'));

    expect(openURL).toHaveBeenCalledWith(webUrl('/your-data'));
  });
});
