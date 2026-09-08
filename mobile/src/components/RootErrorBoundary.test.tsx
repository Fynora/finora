import { Text } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
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

  it('re-renders the children again on "Try again", clearing the fallback', () => {
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
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));

    expect(screen.getByText('recovered')).toBeTruthy();
    expect(screen.queryByText("This screen didn't load correctly")).toBeNull();
  });
});
