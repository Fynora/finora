import { Text } from 'react-native';
import { act, render, waitFor, type RenderAPI } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as SecureStore from 'expo-secure-store';
import { AuthProvider, useAuth } from './AuthContext';

jest.mock('../api/endpoints', () => ({
  authApi: { logout: jest.fn(async () => ({ message: 'ok' })) },
}));
jest.mock('../lib/pushRegistration', () => ({
  registerDeviceToken: jest.fn(),
  revokeDeviceToken: jest.fn(),
  subscribeToForegroundMessages: jest.fn(() => jest.fn()),
}));
jest.mock('../lib/revenueCat', () => ({ configureRevenueCat: jest.fn() }));

/**
 * The greeting and the More menu read `fullName` from AuthContext, which was only ever written at
 * sign-in. Renaming the account anywhere else -- the web app, or Profile on this very device --
 * left it stale until the next login, so the name changed on web and not here.
 *
 * The account's real name is the ['user-settings'] query. The context follows it.
 */

function Probe() {
  const { bootstrapping, fullName } = useAuth();
  return (
    <>
      <Text testID="bootstrapping">{String(bootstrapping)}</Text>
      <Text testID="name">{fullName ?? 'none'}</Text>
    </>
  );
}

let queryClient: QueryClient;

async function renderSignedIn(storedName: string | null): Promise<RenderAPI> {
  await SecureStore.setItemAsync('finora_token', 'stored-token');
  await SecureStore.setItemAsync('finora_email', 'me@example.com');
  await SecureStore.setItemAsync('finora_phone_verified', 'true');
  if (storedName !== null) await SecureStore.setItemAsync('finora_name', storedName);
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  const view = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Probe />
      </AuthProvider>
    </QueryClientProvider>
  );
  await waitFor(() => expect(view.getByTestId('bootstrapping')).toHaveTextContent('false'));
  return view;
}

describe('fullName follows the account', () => {
  it('shows a name changed elsewhere as soon as the profile query returns it', async () => {
    const view = await renderSignedIn('Google Name');
    expect(view.getByTestId('name')).toHaveTextContent('Google Name');

    act(() => {
      queryClient.setQueryData(['user-settings'], { fullName: 'Fynora' });
    });

    await waitFor(() => expect(view.getByTestId('name')).toHaveTextContent('Fynora'));
  });

  it('keeps the new name for the next cold start', async () => {
    const view = await renderSignedIn('Google Name');

    act(() => {
      queryClient.setQueryData(['user-settings'], { fullName: 'Fynora' });
    });

    await waitFor(() => expect(view.getByTestId('name')).toHaveTextContent('Fynora'));
    await waitFor(async () => expect(await SecureStore.getItemAsync('finora_name')).toBe('Fynora'));
  });

  it('ignores a profile with no name rather than blanking the greeting', async () => {
    const view = await renderSignedIn('Google Name');

    act(() => {
      queryClient.setQueryData(['user-settings'], { fullName: '' });
    });
    act(() => {
      queryClient.setQueryData(['user-settings'], {});
    });

    expect(view.getByTestId('name')).toHaveTextContent('Google Name');
  });

  it('does not react to other queries', async () => {
    const view = await renderSignedIn('Google Name');

    act(() => {
      queryClient.setQueryData(['accounts'], { fullName: 'Not The Profile' });
    });

    expect(view.getByTestId('name')).toHaveTextContent('Google Name');
  });

  it('stops following once the session has ended', async () => {
    let auth: ReturnType<typeof useAuth>;
    function Capture() {
      auth = useAuth();
      return null;
    }
    await SecureStore.setItemAsync('finora_token', 'stored-token');
    await SecureStore.setItemAsync('finora_phone_verified', 'true');
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
    const view = render(
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <Probe />
          <Capture />
        </AuthProvider>
      </QueryClientProvider>
    );
    await waitFor(() => expect(view.getByTestId('bootstrapping')).toHaveTextContent('false'));

    await act(async () => {
      auth.logout();
    });
    act(() => {
      queryClient.setQueryData(['user-settings'], { fullName: 'Late Response' });
    });

    expect(view.getByTestId('name')).toHaveTextContent('none');
    expect(await SecureStore.getItemAsync('finora_name')).toBeNull();
  });
});
