import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsCategorizationScreen } from './SettingsCategorizationScreen';
import { workspaceApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({ workspaceApi: { getSettings: jest.fn(), updateSettings: jest.fn() } }));
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate: mockNavigate }) }));

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider><SettingsCategorizationScreen /></ThemeProvider>
    </QueryClientProvider>
  );
}

test('increments the threshold via the accessible stepper', async () => {
  (workspaceApi.getSettings as jest.Mock).mockResolvedValue({ autoApplyConfidenceThreshold: 90 });
  renderScreen();
  await screen.findByText('90%');
  fireEvent.press(screen.getByLabelText('Decrease threshold'));
  await waitFor(() => expect(screen.getByText('85%')).toBeTruthy());
});

test('shows an error message, not a silent 90% default, when workspace settings fail to load', async () => {
  (workspaceApi.getSettings as jest.Mock).mockRejectedValue(new Error('network down'));
  renderScreen();
  expect(await screen.findByText("Couldn't load your settings — please try again later.")).toBeTruthy();
  expect(screen.queryByText('90%')).toBeNull();
});
