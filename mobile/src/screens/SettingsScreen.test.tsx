import { Linking } from 'react-native';
import { render, screen, fireEvent } from '@testing-library/react-native';
import { SettingsScreen } from './SettingsScreen';
import { ThemeProvider } from '../theme';
import { safeStorage } from '../lib/safeStorage';
import { trackNavigation } from '../lib/trackNavigation';
import { webUrl } from '../lib/webUrl';

const mockNavigate = jest.fn();
// The real one queues a network report that outlives the test; only the call itself matters here.
jest.mock('../lib/trackNavigation', () => ({ trackNavigation: jest.fn() }));
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

// Gmail sync is paused (lib/features.ts). SettingsScreen reads the flag at render time, so a getter
// on a mutable holder lets one file cover both states: hidden (the shipped default) and switched on.
// The `mock` prefix is what lets jest's hoisting allow this variable inside the factory.
const mockFeatures = { gmailSyncUiEnabled: false };
jest.mock('../lib/features', () => ({
  get GMAIL_SYNC_UI_ENABLED() { return mockFeatures.gmailSyncUiEnabled; },
}));

beforeEach(() => { mockFeatures.gmailSyncUiEnabled = false; });
afterEach(() => { jest.restoreAllMocks(); });

function renderScreen() {
  return render(<ThemeProvider><SettingsScreen /></ThemeProvider>);
}

test('every category row pushes its own screen', () => {
  renderScreen();
  fireEvent.press(screen.getByText('Security'));
  expect(mockNavigate).toHaveBeenCalledWith('SettingsSecurity');
});

test('offers no Connected Apps row while Gmail sync is paused, and keeps the other six', () => {
  renderScreen();

  expect(screen.queryByText('Connected Apps')).toBeNull();
  for (const label of ['General', 'Security', 'Categorization', 'Data', 'Bank Sync', 'Account']) {
    expect(screen.getByText(label)).toBeTruthy();
  }
});

test('brings the Connected Apps row back, and it opens its screen, when Gmail sync is switched on', () => {
  mockFeatures.gmailSyncUiEnabled = true;
  renderScreen();

  fireEvent.press(screen.getByText('Connected Apps'));
  expect(mockNavigate).toHaveBeenCalledWith('SettingsConnectedApps');
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

test('groups Help & Support and Legal under their own headings, and does not repeat "Settings" above the categories', () => {
  renderScreen();
  expect(screen.getAllByRole('header').map((h) => h.props.children)).toEqual(['Help & Support', 'Legal']);
});

test('the General row shows the theme currently chosen, and no other row shows a value', () => {
  renderScreen();
  // "System" is the provider's default until a stored choice loads.
  expect(screen.getAllByText('System')).toHaveLength(1);
  expect(screen.queryByText('Light')).toBeNull();
  expect(screen.queryByText('Dark')).toBeNull();
});

test('the General row follows a stored theme choice rather than always saying System', async () => {
  jest.spyOn(safeStorage, 'getItem').mockImplementation(async (key: string) => (key === 'finora_theme' ? 'dark' : null));
  renderScreen();
  expect(await screen.findByText('Dark')).toBeTruthy();
  expect(screen.queryByText('System')).toBeNull();
});

test('every row keeps the line explaining it', () => {
  renderScreen();
  for (const description of [
    'Preferences, timezone, theme',
    'Password, verification, active sessions',
    'How confident a suggestion must be to apply on its own',
    'Your imported statements and transaction history',
    'Automatically sync transactions from your linked bank accounts',
    'Deactivate or permanently delete your Fynora account',
    'File a new one, or check on an existing one',
    'A bug, an idea, or anything else on your mind',
  ]) {
    expect(screen.getByText(description)).toBeTruthy();
  }
});

test('My Tickets opens the ticket list and reports the navigation', () => {
  renderScreen();
  fireEvent.press(screen.getByText('My Tickets'));
  expect(mockNavigate).toHaveBeenCalledWith('SupportTickets');
  expect(trackNavigation).toHaveBeenCalledWith('support', 'group');
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
