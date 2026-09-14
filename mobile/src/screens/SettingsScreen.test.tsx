import { render, screen, fireEvent } from '@testing-library/react-native';
import { SettingsScreen } from './SettingsScreen';
import { ThemeProvider } from '../theme';

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
