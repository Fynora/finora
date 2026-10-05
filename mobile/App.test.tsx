import { act, render, screen } from '@testing-library/react-native';
import { LAUNCH_TIMELINE, setLaunchAnimationPlayedForTests } from './src/components/LaunchAnimation';
import { createLaunchUrlGuard } from './src/lib/appLinks';
import App from './App';

// App's wiring is the only thing under test here, so every provider and the navigator are stubbed to
// pass-throughs: what matters is that mounting App -- and only App -- resets the launch-URL guards.
jest.mock('@tanstack/react-query', () => ({ QueryClientProvider: ({ children }: { children: unknown }) => children }));
jest.mock('expo-status-bar', () => ({ StatusBar: () => null }));
// Reanimated's Jest runtime has no frame callbacks; the launch animation then begins from its
// startWaitCap timer instead of its frame gate.
jest.mock('react-native-reanimated', () => {
  const actual = jest.requireActual('react-native-reanimated');
  return {
    __esModule: true,
    ...actual,
    default: actual.default,
    useFrameCallback: () => ({ setActive: () => undefined, isActive: true, callbackId: 0 }),
  };
});
jest.mock('expo-splash-screen', () => ({ preventAutoHideAsync: jest.fn(), hideAsync: jest.fn(), setOptions: jest.fn() }));
jest.mock('expo-share-intent', () => ({ ShareIntentProvider: ({ children }: { children: unknown }) => children }));
jest.mock('react-native-safe-area-context', () => ({ SafeAreaProvider: ({ children }: { children: unknown }) => children }));
jest.mock('./src/api/queryClient', () => ({
  queryClient: {},
  startNetworkMonitoring: jest.fn(),
  startForegroundRefetch: jest.fn(),
  startQueryPersistence: jest.fn(),
}));
jest.mock('./src/lib/fileCacheSweep', () => ({ sweepFileCache: jest.fn() }));
jest.mock('./src/lib/monitoring', () => ({ initMonitoring: jest.fn(), withMonitoring: (component: unknown) => component }));
jest.mock('./src/theme', () => ({
  ...jest.requireActual('./src/theme'),
  ThemeProvider: ({ children }: { children: unknown }) => children,
  useAppFonts: () => [true, null],
}));
jest.mock('./src/components/AppLockGate', () => ({ AppLockGate: ({ children }: { children: unknown }) => children }));
jest.mock('./src/components/OfflineBanner', () => ({ OfflineBoundary: ({ children }: { children: unknown }) => children }));
jest.mock('./src/components/RootWarningBanner', () => ({ RootWarningBoundary: ({ children }: { children: unknown }) => children }));
jest.mock('./src/context/AuthContext', () => ({ AuthProvider: ({ children }: { children: unknown }) => children }));
jest.mock('./src/context/ToastContext', () => ({ ToastProvider: ({ children }: { children: unknown }) => children }));
jest.mock('./src/onboarding/OnboardingStepContext', () => ({ OnboardingStepProvider: ({ children }: { children: unknown }) => children }));
// Counts mounts, so a test can prove the launch animation ending never remounts the app tree.
const mockNavigatorMounts = { count: 0 };
jest.mock('./src/navigation/RootNavigator', () => {
  const { useEffect } = jest.requireActual('react');
  return {
    RootNavigator: () => {
      useEffect(() => {
        mockNavigatorMounts.count += 1;
      }, []);
      return null;
    },
  };
});

describe('App', () => {
  it('forgets the handled launch URLs when it mounts, so a re-created Android activity can act on a repeated link', () => {
    const isFirstDelivery = createLaunchUrlGuard();
    expect(isFirstDelivery('https://app.fynora.net/app/settings')).toBe(true);
    expect(isFirstDelivery('https://app.fynora.net/app/settings')).toBe(false);

    render(<App />);

    expect(isFirstDelivery('https://app.fynora.net/app/settings')).toBe(true);
  });

  it('covers the app with the launch animation on a cold start, and not again on a remount', () => {
    jest.useFakeTimers();
    try {
      setLaunchAnimationPlayedForTests(false);
      mockNavigatorMounts.count = 0;
      const first = render(<App />);
      expect(screen.getByTestId('launch-animation')).toBeOnTheScreen();
      // The app behind it is hidden from screen readers until it has gone.
      expect(screen.getByTestId('app-root', { includeHiddenElements: true }).props.accessibilityElementsHidden).toBe(true);
      expect(screen.getByTestId('app-root', { includeHiddenElements: true }).props.importantForAccessibility).toBe('no-hide-descendants');

      act(() => {
        jest.advanceTimersByTime(
          LAUNCH_TIMELINE.startWaitCap + LAUNCH_TIMELINE.exitStart + LAUNCH_TIMELINE.liftDelay + LAUNCH_TIMELINE.liftDuration + 100,
        );
      });
      expect(screen.queryByTestId('launch-animation')).not.toBeOnTheScreen();
      expect(screen.getByTestId('app-root', { includeHiddenElements: true }).props.accessibilityElementsHidden).toBe(false);
      expect(screen.getByTestId('app-root', { includeHiddenElements: true }).props.importantForAccessibility).toBe('auto');
      // The app underneath stayed mounted the whole time: the overlay going away never remounts it.
      expect(mockNavigatorMounts.count).toBe(1);

      // A re-created Android activity remounts App inside the same JS runtime.
      first.unmount();
      render(<App />);
      expect(screen.queryByTestId('launch-animation')).not.toBeOnTheScreen();
    } finally {
      jest.useRealTimers();
    }
  });

  it('drops the native splash instantly rather than fading it over the launch animation', () => {
    // Android fades by default (400ms, read from `duration`), which hid the stem's whole draw.
    // Called once, when App's module loads, so load a fresh copy here rather than relying on call
    // history surviving from the import at the top of this file.
    let setOptions: jest.Mock | undefined;
    jest.isolateModules(() => {
      require('./App');
      setOptions = require('expo-splash-screen').setOptions;
    });
    expect(setOptions).toHaveBeenCalledWith({ duration: 0, fade: false });
  });
});
