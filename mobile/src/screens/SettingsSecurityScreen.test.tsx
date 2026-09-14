import { render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsSecurityScreen } from './SettingsSecurityScreen';
import { userApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({ userApi: { get: jest.fn() } }));
jest.mock('./settings/AppLockSection', () => ({ AppLockSection: () => null }));
jest.mock('./settings/DeviceSessionsSection', () => ({ DeviceSessionsSection: () => null }));

const PHONE = '+919876543210'; // synthetic-ok: invented test number
const MASKED_PHONE = '+•••••••••210';

test('shows the masked phone number once loaded', async () => {
  (userApi.get as jest.Mock).mockResolvedValue({
    email: 'a@example.com', fullName: 'Amy', phoneNumber: PHONE, phoneVerified: true,
    passwordChangedAt: null, signInMethod: 'PASSWORD', lowBalanceThreshold: 2000, theme: 'system',
    timezone: 'Asia/Kolkata', createdAt: '2026-01-01T00:00:00Z',
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider><SettingsSecurityScreen /></ThemeProvider>
    </QueryClientProvider>
  );
  expect(await screen.findByText(MASKED_PHONE)).toBeTruthy();
});
