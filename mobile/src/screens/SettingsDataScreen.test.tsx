import { render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsDataScreen } from './SettingsDataScreen';
import { analyticsApi, userApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  analyticsApi: { importStatistics: jest.fn() },
  userApi: { get: jest.fn().mockResolvedValue({ signInMethod: 'PASSWORD' }) },
}));
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate: jest.fn() }) }));

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
