import { act, render, screen } from '@testing-library/react-native';
import { useState } from 'react';
import { Linking, Text, type AlertButton } from 'react-native';
import { AppAlert } from '../lib/appAlert';
import {
  BaseNavigationContainer, createNavigatorFactory, useNavigationBuilder, useNavigationContainerRef,
} from '@react-navigation/core';
import { StackRouter } from '@react-navigation/routers';
import { useResetPasswordDeepLink } from './useResetPasswordDeepLink';
import type { RootParamList } from './types';

// @react-navigation/core's index pulls in query-string (URL path parsing for linking, which this test
// never touches), and query-string require()s decode-uri-component -- ESM-only under this repo's
// package.json override. CI runs Node 22, which cannot require() an ES module (Node 24.9+ can, which
// is why this passed on a newer local Node and failed in CI). A plain stand-in keeps the suite
// loadable on every Node the repo supports.
jest.mock('decode-uri-component', () => (value: string) => decodeURIComponent(value));

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
// Each mounted instance takes the next number, so a test can tell a reused screen from a fresh one.
let instanceCounter = 0;
function ResetScreen({ route }: { route: { params?: { token?: string } } }) {
  const [instance] = useState(() => ++instanceCounter);
  return <Text>reset-screen:{route.params?.token}:instance{instance}</Text>;
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
            <Stack.Screen
              name="ResetPassword"
              component={ResetScreen as never}
              // Same rule as RootNavigator: a screen per token.
              getId={({ params }: { params?: object }) => (params as { token?: string } | undefined)?.token}
            />
          </>
        ) : (
          <Stack.Screen name="Home" component={HomeScreen} />
        )}
      </Stack.Navigator>
    </BaseNavigationContainer>
  );
}

const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
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

    expect(screen.getByText(/^reset-screen:tok-real:instance\d+$/)).toBeTruthy();
  });

  it('after a signed-in user confirms, signs out and lands on the reset screen (route set swapped in the same commit)', async () => {
    render(<Harness initialToken="session-token" />);
    await act(async () => {});
    expect(screen.getByText('home-screen')).toBeTruthy();

    await act(async () => { urlListener?.({ url: LINK }); });
    expect(screen.getByText('home-screen')).toBeTruthy();

    const buttons = (alertSpy.mock.calls[0][2] ?? []) as AlertButton[];
    await act(async () => { buttons.find((b) => /sign out/i.test(b.text ?? ''))?.onPress?.(); });

    expect(screen.getByText(/^reset-screen:tok-real:instance\d+$/)).toBeTruthy();
  });

  it('opens a FRESH screen for a newer link, and reuses the open one for the same link', async () => {
    instanceCounter = 0;
    render(<Harness initialToken={null} />);
    await act(async () => {});

    await act(async () => { urlListener?.({ url: 'https://app.fynora.net/reset-password?token=first' }); });
    expect(screen.getByText('reset-screen:first:instance1')).toBeTruthy();

    // Same link again: the open screen is reused, not duplicated.
    await act(async () => { urlListener?.({ url: 'https://app.fynora.net/reset-password?token=first' }); });
    expect(screen.getByText('reset-screen:first:instance1')).toBeTruthy();

    // A newer link (the user requested a reset twice): a new screen, none of the old flow's state.
    await act(async () => { urlListener?.({ url: 'https://app.fynora.net/reset-password?token=second' }); });
    expect(screen.getByText('reset-screen:second:instance2')).toBeTruthy();
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
