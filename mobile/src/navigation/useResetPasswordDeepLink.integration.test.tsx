import { act, render, screen } from '@testing-library/react-native';
import { useState } from 'react';
import { Alert, Linking, Text, type AlertButton } from 'react-native';
import {
  BaseNavigationContainer, createNavigatorFactory, useNavigationBuilder, useNavigationContainerRef,
} from '@react-navigation/core';
import { StackRouter } from '@react-navigation/routers';
import { useResetPasswordDeepLink } from './useResetPasswordDeepLink';
import type { RootParamList } from './types';

// The unit tests fake the navigation ref. This one uses the REAL container and router, to check the
// one thing a fake cannot: that navigate('ResetPassword') issued from RootNavigator's own effect
// lands when the same commit has just swapped the route set (AuthStack mounting on sign-out).
// A minimal navigator built on StackRouter stands in for native-stack, which the Jest pipeline
// cannot load (see RootNavigator.test.tsx).

function TestNavigator({ children }: { children: React.ReactNode }) {
  const { state, descriptors, NavigationContent } = useNavigationBuilder(StackRouter, { children });
  const focused = state.routes[state.index];
  return <NavigationContent>{descriptors[focused.key].render()}</NavigationContent>;
}
const createTestNavigator = createNavigatorFactory(TestNavigator);
const Stack = createTestNavigator();

function LoginScreen() { return <Text>login-screen</Text>; }
function HomeScreen() { return <Text>home-screen</Text>; }
function ResetScreen({ route }: { route: { params?: { token?: string } } }) {
  return <Text>reset-screen:{route.params?.token}</Text>;
}

let setToken: (t: string | null) => void = () => {};

// Shaped like RootNavigator: the hook lives in the component that OWNS the container, and the
// screen set depends on auth state.
function Harness({ initialToken }: { initialToken: string | null }) {
  const [token, setTokenState] = useState<string | null>(initialToken);
  setToken = setTokenState;
  const ref = useNavigationContainerRef<RootParamList>();
  const { onNavigationReady } = useResetPasswordDeepLink(ref, {
    bootstrapping: false,
    signedIn: token !== null,
    signOut: () => setTokenState(null),
  });
  return (
    <BaseNavigationContainer ref={ref} onReady={onNavigationReady}>
      <Stack.Navigator>
        {token === null ? (
          <>
            <Stack.Screen name="Login" component={LoginScreen} />
            <Stack.Screen name="ResetPassword" component={ResetScreen as never} />
          </>
        ) : (
          <Stack.Screen name="Home" component={HomeScreen} />
        )}
      </Stack.Navigator>
    </BaseNavigationContainer>
  );
}

const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
const getInitialURLSpy = jest.spyOn(Linking, 'getInitialURL');
const addEventListenerSpy = jest.spyOn(Linking, 'addEventListener');
let urlListener: ((event: { url: string }) => void) | null = null;

const LINK = 'https://app.fynora.net/reset-password?token=tok-real';

describe('useResetPasswordDeepLink against the real React Navigation container', () => {
  beforeEach(() => {
    urlListener = null;
    alertSpy.mockClear();
    getInitialURLSpy.mockReset().mockResolvedValue(null);
    addEventListenerSpy.mockReset().mockImplementation((_e, listener) => {
      urlListener = listener as (event: { url: string }) => void;
      return { remove: jest.fn() } as never;
    });
  });

  it('opens the reset screen with the token when signed out', async () => {
    render(<Harness initialToken={null} />);
    await act(async () => {});

    await act(async () => { urlListener?.({ url: LINK }); });

    expect(screen.getByText('reset-screen:tok-real')).toBeTruthy();
  });

  it('after a signed-in user confirms, signs out and lands on the reset screen (route set swapped in the same commit)', async () => {
    render(<Harness initialToken="session-token" />);
    await act(async () => {});
    expect(screen.getByText('home-screen')).toBeTruthy();

    await act(async () => { urlListener?.({ url: LINK }); });
    expect(screen.getByText('home-screen')).toBeTruthy();

    const buttons = (alertSpy.mock.calls[0][2] ?? []) as AlertButton[];
    await act(async () => { buttons.find((b) => /sign out/i.test(b.text ?? ''))?.onPress?.(); });

    expect(screen.getByText('reset-screen:tok-real')).toBeTruthy();
  });

  it('leaves a signed-in user exactly where they were on cancel', async () => {
    render(<Harness initialToken="session-token" />);
    await act(async () => {});

    await act(async () => { urlListener?.({ url: LINK }); });
    const buttons = (alertSpy.mock.calls[0][2] ?? []) as AlertButton[];
    await act(async () => { buttons.find((b) => b.style === 'cancel')?.onPress?.(); });
    await act(async () => { setToken(null); });

    expect(screen.queryByText(/reset-screen/)).toBeNull();
    expect(screen.getByText('login-screen')).toBeTruthy();
  });
});
