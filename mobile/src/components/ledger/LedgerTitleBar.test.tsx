import { Platform } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { LedgerPinnedBar, LedgerTitleBlock } from './LedgerTitleBar';
import { ThemeProvider } from '../../theme';
import { light } from '../../theme/palette';

jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => false) }));
const { useReduceTransparency } = jest.requireMock('../../lib/useReduceTransparency');
// A stand-in for the native glass view that keeps its props readable.
jest.mock('expo-glass-effect', () => {
  const React = require('react');
  const { View } = require('react-native');
  return {
    GlassView: (props: object) => React.createElement(View, { testID: 'ledger-pinned-glass', ...props }),
    isLiquidGlassAvailable: jest.fn(() => true),
    isGlassEffectAPIAvailable: jest.fn(() => true),
  };
});
const glass = jest.requireMock('expo-glass-effect');

const originalOS = Platform.OS;
afterEach(() => {
  (Platform as { OS: string }).OS = originalOS;
  useReduceTransparency.mockReturnValue(false);
  glass.isLiquidGlassAvailable.mockReturnValue(true);
  jest.clearAllMocks();
});

describe('LedgerTitleBlock', () => {
  it('shows the title as a heading, the count, and the add button', () => {
    const onAdd = jest.fn();
    render(<ThemeProvider><LedgerTitleBlock countLabel="392 transactions" onAdd={onAdd} /></ThemeProvider>);
    expect(screen.getByRole('header', { name: 'Transactions' })).toBeTruthy();
    expect(screen.getByText('392 transactions')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Add transaction'));
    expect(onAdd).toHaveBeenCalledTimes(1);
  });
});

describe('LedgerTitleBlock at large text', () => {
  it('keeps the title on one line and shrinks it to fit, never breaking the word', () => {
    // Seen at accessibility-large on an iPhone 17 Pro simulator: "Transactio" / "ns".
    render(<ThemeProvider><LedgerTitleBlock countLabel="392 transactions" onAdd={jest.fn()} /></ThemeProvider>);
    const title = screen.getByRole('header', { name: 'Transactions' });
    expect(title.props.numberOfLines).toBe(1);
    expect(title.props.adjustsFontSizeToFit).toBe(true);
    expect(title.props.maxFontSizeMultiplier).toBe(1.4);
  });
});

describe('LedgerPinnedBar', () => {
  const handlers = { onSearch: jest.fn(), onAdd: jest.fn() };
  const bar = (shown: boolean) => (
    <ThemeProvider><LedgerPinnedBar shown={shown} topInset={59} {...handlers} /></ThemeProvider>
  );

  it('covers the status bar area and 54 points under it', () => {
    render(bar(false));
    expect(screen.getByTestId('ledger-pinned-bar')).toHaveStyle({ position: 'absolute', top: 0, height: 113 });
  });

  it('at rest lets touches through and hides its buttons from everyone', () => {
    render(bar(false));
    expect(screen.getByTestId('ledger-pinned-bar').props.pointerEvents).toBe('none');
    expect(screen.queryByLabelText('Search transactions')).toBeNull();
    expect(screen.queryByLabelText('Add transaction')).toBeNull();
  });

  it('once the list has moved, offers the title, search and add', () => {
    // The surface and the contents come together: found on the simulator, a frosted bar without
    // them hid the large title and its add button and offered nothing in their place.
    render(bar(true));
    expect(screen.getByText('Transactions')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Search transactions'));
    fireEvent.press(screen.getByLabelText('Add transaction'));
    expect(handlers.onSearch).toHaveBeenCalledTimes(1);
    expect(handlers.onAdd).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('ledger-pinned-bar').props.pointerEvents).toBe('box-none');
  });

  it('stops the pinned title growing past 1.3 times', () => {
    render(bar(true));
    expect(screen.getByText('Transactions').props.maxFontSizeMultiplier).toBe(1.3);
  });

  it('on Liquid Glass changes the glass style with the library animation, never by opacity', () => {
    const view = render(bar(false));
    expect(screen.getByTestId('ledger-pinned-glass').props.glassEffectStyle)
      .toEqual({ style: 'none', animate: true, animationDuration: 0.15 });
    view.rerender(bar(true));
    const g = screen.getByTestId('ledger-pinned-glass');
    expect(g.props.glassEffectStyle).toEqual({ style: 'regular', animate: true, animationDuration: 0.15 });
    expect(g).not.toHaveStyle({ opacity: 0 });
    expect(screen.getByTestId('ledger-pinned-bar')).not.toHaveStyle({ opacity: 0 });
  });

  it('is a solid card-coloured bar under Reduce Transparency, with no glass view', () => {
    useReduceTransparency.mockReturnValue(true);
    render(bar(true));
    expect(screen.queryByTestId('ledger-pinned-glass')).toBeNull();
    expect(screen.getByTestId('ledger-pinned-fill', { includeHiddenElements: true })).toHaveStyle({ backgroundColor: light.card });
  });

  it('is a solid bar on Android, where a see-through fill would show the list through it', () => {
    (Platform as { OS: string }).OS = 'android';
    render(bar(true));
    expect(screen.queryByTestId('ledger-pinned-glass')).toBeNull();
    expect(screen.getByTestId('ledger-pinned-fill', { includeHiddenElements: true })).toHaveStyle({ backgroundColor: light.card });
  });

  it('on iOS without Liquid Glass shows a blur only while solid', () => {
    glass.isLiquidGlassAvailable.mockReturnValue(false);
    const view = render(bar(false));
    expect(screen.queryByTestId('ledger-pinned-blur', { includeHiddenElements: true })).toBeNull();
    view.rerender(bar(true));
    expect(screen.getByTestId('ledger-pinned-blur', { includeHiddenElements: true })).toBeTruthy();
  });
});
