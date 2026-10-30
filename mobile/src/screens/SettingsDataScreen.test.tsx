import { render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsDataScreen } from './SettingsDataScreen';
import { analyticsApi, userApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';
import { never } from '../test/loadingStates';

jest.mock('../api/endpoints', () => ({
  analyticsApi: { importStatistics: jest.fn() },
  userApi: { get: jest.fn().mockResolvedValue({ signInMethod: 'PASSWORD' }) },
  statementPasswordsApi: { list: jest.fn().mockResolvedValue({ saveAvailable: false, items: [] }) },
}));

test('renders total statements once loaded', async () => {
  (analyticsApi.importStatistics as jest.Mock).mockResolvedValue({
    totalStatements: 12, totalTransactionsImported: 340, totalTransactionsSkipped: 2, lastImportedAt: '2026-08-01T00:00:00Z',
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider><SettingsDataScreen /></ThemeProvider>
    </QueryClientProvider>
  );
  expect(await screen.findByText('12')).toBeTruthy();
});

// The export sheet asks for a password or a Google/Apple check depending on how the account signs
// in, so Export stays off until that is known: guessing "password" would show a Google user a field
// they can never fill in.
test('keeps Export disabled until the account has loaded, then enables it', async () => {
  (analyticsApi.importStatistics as jest.Mock).mockResolvedValue({
    totalStatements: 1, totalTransactionsImported: 2, totalTransactionsSkipped: 3, lastImportedAt: null,
  });
  jest.mocked(userApi.get).mockReturnValueOnce(never());
  const pending = render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ThemeProvider><SettingsDataScreen /></ThemeProvider>
    </QueryClientProvider>
  );
  expect(screen.getByRole('button', { name: 'Export' }).props.accessibilityState.disabled).toBe(true);
  pending.unmount();

  jest.mocked(userApi.get).mockResolvedValueOnce({ signInMethod: 'GOOGLE' } as never);
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ThemeProvider><SettingsDataScreen /></ThemeProvider>
    </QueryClientProvider>
  );
  await waitFor(() =>
    expect(screen.getByRole('button', { name: 'Export' }).props.accessibilityState.disabled).toBe(false)
  );
});
