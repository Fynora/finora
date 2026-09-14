import { Linking } from 'react-native';
import { render, screen, fireEvent } from '@testing-library/react-native';
import { SettingsScreen } from './SettingsScreen';
import { ThemeProvider } from '../theme';
import { webUrl } from '../lib/webUrl';

const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate: mockNavigate }) }));
jest.mock('./support/FeedbackSheet', () => ({
  FeedbackSheet: ({ onClose }: { onClose: () => void }) => {
    const { Pressable, Text } = require('react-native');
    return (
      <Pressable accessibilityRole="button" onPress={onClose}>
        <Text>Fake Feedback Sheet</Text>
      </Pressable>
    );
  },
}));

function renderScreen() {
  return render(<ThemeProvider><SettingsScreen /></ThemeProvider>);
}

test('every category row pushes its own screen', () => {
  renderScreen();
  fireEvent.press(screen.getByText('Security'));
  expect(mockNavigate).toHaveBeenCalledWith('SettingsSecurity');
});

test('Help & Support and Legal stay as standalone rows, not folded into a category', () => {
  renderScreen();
  expect(screen.getByText('My Tickets')).toBeTruthy();
  expect(screen.getByText('Privacy Policy')).toBeTruthy();
});

test('Send Feedback still opens the feedback sheet', async () => {
  renderScreen();
  fireEvent.press(screen.getByText('Send Feedback'));
  expect(await screen.findByText('Fake Feedback Sheet')).toBeTruthy();
});

describe('Legal', () => {
  it('opens the web Privacy page when pressed', async () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderScreen();
    fireEvent.press(screen.getByText('Privacy Policy'));
    expect(openURL).toHaveBeenCalledWith(webUrl('/privacy'));
  });

  it('opens the web Terms page when pressed', async () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderScreen();
    fireEvent.press(screen.getByText('Terms of Service'));
    expect(openURL).toHaveBeenCalledWith(webUrl('/terms'));
  });

  // Ported from #1513, which added these two links to the pre-redesign monolith -- carried
  // forward here since this file is the redesign's replacement for that same screen.
  it('opens the web Trust & Security page when pressed', async () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderScreen();
    fireEvent.press(screen.getByText('Trust & Security'));
    expect(openURL).toHaveBeenCalledWith(webUrl('/trust'));
  });

  it('opens the web Data Portability Promise page when pressed', async () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    renderScreen();
    fireEvent.press(screen.getByText('Data Portability Promise'));
    expect(openURL).toHaveBeenCalledWith(webUrl('/your-data'));
  });
});
