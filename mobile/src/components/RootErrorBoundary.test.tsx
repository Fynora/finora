import { Text } from 'react-native';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { RootErrorBoundary } from './RootErrorBoundary';
import { reportHandledError } from '../lib/monitoring';
import { ThemeProvider } from '../theme';

jest.mock('../lib/monitoring', () => ({
  reportHandledError: jest.fn(),
}));

const mockedReportHandledError = reportHandledError as jest.MockedFunction<typeof reportHandledError>;

/**
 * The behavior under test: a render error below RootErrorBoundary becomes a recovery panel
 * instead of taking the whole app down with no fallback UI -- see this component's own doc
 * comment for why nothing else in the tree catches this (Sentry.wrap adds no boundary).
 *
 * React logs a caught error to console.error regardless, and componentDidCatch adds its own line
 * on top -- both silenced here so the suite's own output doesn't read as a failure when it passes.
 */
let consoleError: jest.SpyInstance;
beforeEach(() => {
  consoleError = jest.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  consoleError.mockRestore();
});

function Boom(): never {
  throw new Error('render blew up');
}

function renderBoundary(children: React.ReactNode) {
  return render(<ThemeProvider><RootErrorBoundary>{children}</RootErrorBoundary></ThemeProvider>);
}

describe('RootErrorBoundary', () => {
  it('renders its children untouched when nothing throws', () => {
    renderBoundary(<Text>the real screen</Text>);

    expect(screen.getByText('the real screen')).toBeTruthy();
  });

  it('shows a recovery panel instead of nothing when a child throws', () => {
    renderBoundary(<Boom />);

    expect(screen.getByText("This screen didn't load correctly")).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  });

  /** A finance app going blank reads as "my data is gone", so the copy has to say otherwise. */
  it('tells the user their financial data is unaffected', () => {
    renderBoundary(<Boom />);

    expect(screen.getByText(/Nothing has been lost/)).toBeTruthy();
  });

  it('reports the failure so it is visible without the user filing a report', () => {
    renderBoundary(<Boom />);

    expect(mockedReportHandledError).toHaveBeenCalledTimes(1);
    expect(mockedReportHandledError.mock.calls[0][1]).toBe('root-navigator');
  });

  it('re-renders the children again on "Try again", clearing the fallback', async () => {
    // A shared flag, not a call counter -- React re-invokes a throwing component's render an
    // extra time internally to classify the error, and a counter would burn that extra call
    // before this test's own fireEvent.press ever fires, making the assertion nondeterministic.
    // Flipping the flag directly from outside keeps every render before "Try again" throwing
    // (matching the other tests here) and every render after it clean, regardless of exactly how
    // many times React itself calls this function under the hood.
    const shouldThrow = { current: true };
    function ThrowsUntilCleared() {
      if (shouldThrow.current) throw new Error('blew up');
      return <Text>recovered</Text>;
    }

    renderBoundary(<ThrowsUntilCleared />);
    expect(screen.getByText("This screen didn't load correctly")).toBeTruthy();

    shouldThrow.current = false;
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    });

    expect(screen.getByText('recovered')).toBeTruthy();
    expect(screen.queryByText("This screen didn't load correctly")).toBeNull();
  });

  // Regression test: RootNavigator hands useNavigationStatePersistence's persisted route straight
  // to NavigationContainer as `initialState` on every mount. Without clearing it first, "Try
  // again" would remount RootNavigator right back onto the exact screen that just crashed --
  // reproducing the same crash instead of recovering from it.
  it('clears the persisted navigation state before remounting its children', async () => {
    await AsyncStorage.setItem('finora_nav_state', JSON.stringify({ index: 0, routes: [{ name: 'CrashedScreen' }] }));

    renderBoundary(<Boom />);
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    });

    expect(await AsyncStorage.getItem('finora_nav_state')).toBeNull();
  });

  // Regression test: reset() used to await the storage clear unguarded -- a rejected
  // AsyncStorage.removeItem would have left `hasError` stuck true forever, making "Try again"
  // permanently non-functional instead of merely failing to clear stale state.
  it('still recovers when clearing the persisted navigation state fails', async () => {
    jest.spyOn(AsyncStorage, 'removeItem').mockRejectedValueOnce(new Error('disk full'));
    const shouldThrow = { current: true };
    function ThrowsUntilCleared() {
      if (shouldThrow.current) throw new Error('blew up');
      return <Text>recovered</Text>;
    }

    renderBoundary(<ThrowsUntilCleared />);
    shouldThrow.current = false;
    await act(async () => {
      fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    });

    expect(screen.getByText('recovered')).toBeTruthy();
  });
});
