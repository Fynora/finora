import type { ComponentType, ReactNode } from 'react';
import { render, screen } from '@testing-library/react-native';
import { RootNavigator } from './RootNavigator';
import { useResetPasswordDeepLink } from './useResetPasswordDeepLink';
import { useAuth } from '../context/AuthContext';
import { useOnboardingStep } from '../onboarding/OnboardingStepContext';
import { useChangePolling } from '../lib/useChangePolling';

jest.mock('../context/AuthContext', () => ({
  useAuth: jest.fn(),
}));

const mockFonts = { ready: true };
jest.mock('../theme', () => ({
  useTheme: () => ({ bg: '#fff', primary: '#000', card: '#fff', ink: '#000', border: '#ccc', muted: '#888' }),
  useThemeSetting: () => ({ resolved: 'light' }),
  useFontsReady: () => mockFonts.ready,
}));

// @react-navigation/native's own real NavigationContainer/useNavigationContainerRef pull in
// enough of the same unbuilt-ESM/native-stack machinery that this test hits the same pre-existing
// gap the native-stack mock below exists for -- see that mock's own comment. Stubbed to the
// minimum RootNavigator itself actually calls: NavigationContainer as a pass-through wrapper,
// useNavigationContainerRef as a plain ref, DefaultTheme/DarkTheme as the two plain objects
// RootNavigator spreads into its own navTheme.
jest.mock('@react-navigation/native', () => ({
  NavigationContainer: ({ children }: { children: ReactNode }) => children,
  useNavigationContainerRef: () => ({ current: null }),
  DefaultTheme: { colors: {}, fonts: {} },
  DarkTheme: { colors: {}, fonts: {} },
}));

// Needs a QueryClient this file never provides; its own behavior is useChangePolling.test.tsx's job.
// Only the wiring -- when RootNavigator switches it on -- is asserted here.
jest.mock('../lib/useChangePolling', () => ({ useChangePolling: jest.fn() }));

jest.mock('./useAuthStackInitialRoute', () => ({
  useAuthStackInitialRoute: () => 'AuthEntry',
}));

jest.mock('./useEmailChangeDeepLink', () => ({
  useEmailChangeDeepLink: () => ({ onNavigationReady: jest.fn() }),
}));

jest.mock('./useReferralDeepLink', () => ({
  useReferralDeepLink: () => ({ onNavigationReady: jest.fn() }),
}));

jest.mock('./useResetPasswordDeepLink', () => ({
  useResetPasswordDeepLink: jest.fn(() => ({ onNavigationReady: jest.fn() })),
}));

jest.mock('./usePushNotificationNavigation', () => ({
  usePushNotificationNavigation: () => ({ onNavigationReady: jest.fn() }),
}));

jest.mock('./useShareIntentDeepLink', () => ({
  useShareIntentDeepLink: () => ({ onNavigationReady: jest.fn() }),
}));

// @react-navigation/native-stack's published "main" entry is an unbuilt ESM file, which this
// project's Jest/Babel pipeline can't load directly (a pre-existing gap, unrelated to this
// feature -- no prior test exercised this import path since RootNavigator had no test file
// before this one). Mocked here, scoped to this file only, rather than touching the shared Jest
// config: Navigator renders its children and Screen renders its component unconditionally, which
// is enough to prove which of RootNavigator's own top-level branches (Auth/VerifyPhone/
// Onboarding/AppTabs) is selected -- the thing this test file is actually about.
// Every rendered <Screen>'s props are recorded by route name, so a test can assert on options the
// rendering itself doesn't exercise (getId).
const mockScreenProps: Record<string, { getId?: (arg: { params?: { token?: string } }) => string | undefined }> = {};
jest.mock('@react-navigation/native-stack', () => ({
  createNativeStackNavigator: () => ({
    Navigator: ({ children }: { children: ReactNode }) => children,
    Screen: (props: { name: string; component?: ComponentType; children?: () => ReactNode }) => {
      const Component = props.component;
      mockScreenProps[props.name] = props as never;
      return Component ? <Component /> : <>{props.children?.()}</>;
    },
  }),
}));

// The required spending question: a marker for the screen, and the hook's answer set per test.
jest.mock('../onboarding/SpendingTrackingQuestionScreen', () => ({
  SpendingTrackingQuestionScreen: () => {
    const { Text } = require('react-native');
    return <Text testID="spending-question">SpendingQuestion</Text>;
  },
}));
const mockSpendingQuestion = { needsAnswer: false, pending: false, submit: jest.fn() };
const mockUseSpendingQuestion = jest.fn((_enabled: boolean, _userKey: string | null) => mockSpendingQuestion);
jest.mock('../onboarding/useSpendingQuestion', () => ({
  useSpendingQuestion: (enabled: boolean, userKey: string | null) => mockUseSpendingQuestion(enabled, userKey),
}));

jest.mock('./AppTabs', () => ({
  AppTabs: () => {
    const { Text } = require('react-native');
    return <Text testID="app-tabs">AppTabs</Text>;
  },
}));

// Same reasoning as AppTabs above: RootNavigator.test.tsx is about which top-level branch gets
// selected, not OnboardingNavigator's own screens (which pull in real Button/theme styling this
// file's minimal '../theme' mock doesn't provide).
jest.mock('../onboarding/OnboardingNavigator', () => ({
  OnboardingNavigator: () => {
    const { Text } = require('react-native');
    return <Text testID="onboarding-navigator">OnboardingNavigator</Text>;
  },
}));

// A marker, like AppTabs above: this file checks WHERE the one-time referral prompt is mounted,
// not the prompt itself (ReferralCodePrompt.test.tsx covers that).
jest.mock('../components/ReferralCodePrompt', () => ({
  ReferralCodePrompt: () => {
    const { Text } = require('react-native');
    return <Text testID="referral-code-prompt">ReferralCodePrompt</Text>;
  },
}));

jest.mock('../onboarding/OnboardingStepContext', () => ({
  useOnboardingStep: jest.fn(),
}));

jest.mock('../onboarding/TourOverlay', () => ({
  TourOverlay: () => {
    const { Text } = require('react-native');
    return <Text testID="tour-overlay">TourOverlay</Text>;
  },
}));

jest.mock('../onboarding/TourTargetRegistry', () => ({
  TourTargetProvider: ({ children }: { children: ReactNode }) => children,
}));

// RootNavigator imports these five screens directly (not lazily), and every one of them pulls in
// real styling that reads spacing/radius off '../theme' at module-load time -- which the mock
// above doesn't provide (it only covers what RootNavigator itself calls, useTheme/
// useThemeSetting). Stubbed for the same reason AppTabs is: this test is about which top-level
// branch RootNavigator selects, not any individual screen's own rendering.
jest.mock('../screens/AuthEntryScreen', () => ({ AuthEntryScreen: () => null }));
jest.mock('../screens/LoginScreen', () => ({ LoginScreen: () => null }));
jest.mock('../screens/RegisterScreen', () => ({ RegisterScreen: () => null }));
jest.mock('../screens/ForgotPasswordScreen', () => ({ ForgotPasswordScreen: () => null }));
jest.mock('../screens/ResetPasswordScreen', () => ({
  ResetPasswordScreen: () => {
    const { Text } = require('react-native');
    return <Text testID="reset-password-screen">ResetPasswordScreen</Text>;
  },
}));
jest.mock('../screens/VerifyPhoneScreen', () => ({ VerifyPhoneScreen: () => null }));

const mockedUseAuth = useAuth as jest.MockedFunction<typeof useAuth>;
const mockedUseChangePolling = useChangePolling as jest.MockedFunction<typeof useChangePolling>;
const mockedUseOnboardingStep = useOnboardingStep as jest.MockedFunction<typeof useOnboardingStep>;

function authState(overrides: Partial<ReturnType<typeof useAuth>> = {}): ReturnType<typeof useAuth> {
  return {
    bootstrapping: false,
    token: null,
    email: null,
    fullName: null,
    phoneVerified: false,
    onboardingCompleted: false,
    login: jest.fn(),
    reactivate: jest.fn(),
    register: jest.fn(),
    loginWithGoogle: jest.fn(),
    loginWithApple: jest.fn(),
    setPhoneVerified: jest.fn(),
    setOnboardingCompleted: jest.fn(),
    logout: jest.fn(),
    ...overrides,
  } as ReturnType<typeof useAuth>;
}

describe('RootNavigator', () => {
  beforeEach(() => {
    mockedUseOnboardingStep.mockReturnValue({ step: 'welcome', setStep: jest.fn() });
    mockSpendingQuestion.needsAnswer = false;
    mockSpendingQuestion.pending = false;
    mockUseSpendingQuestion.mockClear();
    mockFonts.ready = true;
  });

  it('shows only the required spending question, in place of the app, until it is answered', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true }));
    mockSpendingQuestion.needsAnswer = true;

    render(<RootNavigator />);

    expect(screen.getByTestId('spending-question')).toBeTruthy();
    expect(screen.queryByTestId('app-tabs')).toBeNull();
    expect(screen.queryByTestId('referral-code-prompt')).toBeNull();
  });

  it('shows the spending question before onboarding too', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: false }));
    mockSpendingQuestion.needsAnswer = true;

    render(<RootNavigator />);

    expect(screen.getByTestId('spending-question')).toBeTruthy();
    expect(screen.queryByTestId('onboarding-navigator')).toBeNull();
  });

  it('holds someone not yet onboarded on a loading screen while the answer is looked up', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: false }));
    mockSpendingQuestion.pending = true;

    render(<RootNavigator />);

    expect(screen.getByTestId('spending-question-loading')).toBeTruthy();
    expect(screen.queryByTestId('onboarding-navigator')).toBeNull();
  });

  it('does not hold a returning user while the answer is looked up', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true }));
    mockSpendingQuestion.pending = true;

    render(<RootNavigator />);

    expect(screen.getByTestId('app-tabs')).toBeTruthy();
    expect(screen.queryByTestId('spending-question-loading')).toBeNull();
  });

  it('asks only a signed-in, verified account, keyed to that account', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: false, onboardingCompleted: false }));
    render(<RootNavigator />);
    expect(mockUseSpendingQuestion.mock.calls.at(-1)?.[0]).toBe(false);
    // Unverified: VerifyPhone comes first even if the question would be needed.
    expect(screen.queryByTestId('spending-question')).toBeNull();

    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true, email: 'a@example.com' }));
    render(<RootNavigator />);
    expect(mockUseSpendingQuestion.mock.calls.at(-1)).toEqual([true, 'a@example.com']);
  });

  it('holds every screen until the fonts are in, without holding back the deep-link hooks', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true }));
    mockFonts.ready = false;
    (useResetPasswordDeepLink as jest.Mock).mockClear();

    const { rerender } = render(<RootNavigator />);

    // A screen laid out before its font registers keeps the system font on iOS, so none mounts yet.
    expect(screen.queryByTestId('app-tabs')).toBeNull();
    expect(useResetPasswordDeepLink).toHaveBeenCalled();

    mockFonts.ready = true;
    rerender(<RootNavigator />);

    expect(screen.getByTestId('app-tabs')).toBeTruthy();
  });

  it('mounts AppTabs when signed in, verified, and onboarded', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true }));

    render(<RootNavigator />);

    expect(screen.getByTestId('app-tabs')).toBeTruthy();
    expect(screen.queryByTestId('tour-overlay')).toBeNull();
    expect(screen.getByTestId('referral-code-prompt')).toBeTruthy();
  });

  it('mounts OnboardingNavigator when signed in, verified, but onboarding is not complete and step is not tour', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: false }));

    render(<RootNavigator />);

    expect(screen.getByTestId('onboarding-navigator')).toBeTruthy();
    expect(screen.queryByTestId('app-tabs')).toBeNull();
    expect(screen.queryByTestId('referral-code-prompt')).toBeNull();
  });

  it('mounts the REAL AppTabs plus TourOverlay when the onboarding step is tour', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: false }));
    mockedUseOnboardingStep.mockReturnValue({ step: 'tour', setStep: jest.fn() });

    render(<RootNavigator />);

    expect(screen.getByTestId('app-tabs')).toBeTruthy();
    expect(screen.getByTestId('tour-overlay')).toBeTruthy();
    expect(screen.queryByTestId('onboarding-navigator')).toBeNull();
    // Never on top of the tour.
    expect(screen.queryByTestId('referral-code-prompt')).toBeNull();
  });

  it('offers the reset-password screen in the signed-out stack, and only there', () => {
    mockedUseAuth.mockReturnValue(authState({ token: null }));
    const signedOut = render(<RootNavigator />);
    expect(screen.getByTestId('reset-password-screen')).toBeTruthy();
    signedOut.unmount();

    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true }));
    render(<RootNavigator />);
    expect(screen.queryByTestId('reset-password-screen')).toBeNull();
  });

  it('identifies a reset screen by its token, so a newer link opens a fresh screen instead of reusing the old flow\'s state', () => {
    mockedUseAuth.mockReturnValue(authState({ token: null }));

    render(<RootNavigator />);

    const getId = mockScreenProps.ResetPassword?.getId;
    expect(getId).toBeDefined();
    expect(getId?.({ params: { token: 'first-link' } })).toBe('first-link');
    expect(getId?.({ params: { token: 'second-link' } })).toBe('second-link');
  });

  it('hands the reset-link hook the real auth state and logout, so a signed-in phone can be signed out first', () => {
    const logout = jest.fn();
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true, logout }));

    render(<RootNavigator />);

    expect(useResetPasswordDeepLink).toHaveBeenLastCalledWith(
      expect.anything(),
      { bootstrapping: false, signedIn: true, signOut: logout },
    );

    mockedUseAuth.mockReturnValue(authState({ token: null, bootstrapping: true, logout }));
    render(<RootNavigator />);

    expect(useResetPasswordDeepLink).toHaveBeenLastCalledWith(
      expect.anything(),
      { bootstrapping: true, signedIn: false, signOut: logout },
    );
  });

  it('still routes to VerifyPhone when unverified, before onboarding is ever considered', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: false, onboardingCompleted: false }));

    render(<RootNavigator />);

    expect(screen.queryByTestId('onboarding-navigator')).toBeNull();
    expect(screen.queryByTestId('app-tabs')).toBeNull();
    expect(screen.queryByTestId('referral-code-prompt')).toBeNull();
  });

  describe('change polling', () => {
    const lastEnabled = () => mockedUseChangePolling.mock.calls.at(-1)?.[0];

    beforeEach(() => mockedUseChangePolling.mockClear());

    it('is off while the spending question replaces the app, and while it is still being looked up', () => {
      mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true }));
      mockSpendingQuestion.needsAnswer = true;
      render(<RootNavigator />);
      expect(lastEnabled()).toBe(false);

      mockSpendingQuestion.needsAnswer = false;
      mockSpendingQuestion.pending = true;
      render(<RootNavigator />);
      // The usual screens show while it is looked up, but nothing navigates into them yet.
      expect(screen.getAllByTestId('app-tabs').length).toBeGreaterThan(0);
      expect(lastEnabled()).toBe(false);
    });

    it('is on when the app tabs are showing', () => {
      mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true }));
      render(<RootNavigator />);
      expect(lastEnabled()).toBe(true);
    });

    it('is on during the tour, which shows the real app tabs', () => {
      mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: false }));
      mockedUseOnboardingStep.mockReturnValue({ step: 'tour', setStep: jest.fn() });
      render(<RootNavigator />);
      expect(lastEnabled()).toBe(true);
    });

    it('is off when signed out', () => {
      mockedUseAuth.mockReturnValue(authState({ token: null }));
      render(<RootNavigator />);
      expect(lastEnabled()).toBe(false);
    });

    it('is off while the phone is unverified: the backend would answer 403 to every poll', () => {
      mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: false }));
      render(<RootNavigator />);
      expect(lastEnabled()).toBe(false);
    });

    it('is off during onboarding before the tour', () => {
      mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: false }));
      render(<RootNavigator />);
      expect(lastEnabled()).toBe(false);
    });
  });
});
