import { render } from '@testing-library/react-native';
import { createLaunchUrlGuard } from './src/lib/appLinks';
import App from './App';

// App's wiring is the only thing under test here, so every provider and the navigator are stubbed to
// pass-throughs: what matters is that mounting App -- and only App -- resets the launch-URL guards.
jest.mock('@tanstack/react-query', () => ({ QueryClientProvider: ({ children }: { children: unknown }) => children }));
jest.mock('expo-status-bar', () => ({ StatusBar: () => null }));
jest.mock('expo-splash-screen', () => ({ preventAutoHideAsync: jest.fn(), hideAsync: jest.fn() }));
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
jest.mock('./src/navigation/RootNavigator', () => ({ RootNavigator: () => null }));

describe('App', () => {
  it('forgets the handled launch URLs when it mounts, so a re-created Android activity can act on a repeated link', () => {
    const isFirstDelivery = createLaunchUrlGuard();
    expect(isFirstDelivery('https://app.fynora.net/app/settings')).toBe(true);
    expect(isFirstDelivery('https://app.fynora.net/app/settings')).toBe(false);

    render(<App />);

    expect(isFirstDelivery('https://app.fynora.net/app/settings')).toBe(true);
  });
});
