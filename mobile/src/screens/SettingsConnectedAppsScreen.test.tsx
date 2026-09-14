import { render, screen } from '@testing-library/react-native';
import { SettingsConnectedAppsScreen } from './SettingsConnectedAppsScreen';
import { ThemeProvider } from '../theme';

jest.mock('./settings/GmailConnectionSection', () => ({
  GmailConnectionSection: () => {
    const { Text } = require('react-native');
    return <Text>Gmail section</Text>;
  },
}));

test('renders the Connected Apps section shell around GmailConnectionSection', () => {
  render(<ThemeProvider><SettingsConnectedAppsScreen /></ThemeProvider>);
  expect(screen.getByText('Gmail section')).toBeTruthy();
  expect(screen.getByText('Connected Apps')).toBeTruthy();
});
