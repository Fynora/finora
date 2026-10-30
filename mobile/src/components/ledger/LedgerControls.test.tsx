import { createRef } from 'react';
import { StyleSheet, type TextInputInstance } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { LedgerControls, type ActiveFilterChip } from './LedgerControls';
import { ThemeProvider } from '../../theme';
import { expectHaptics } from '../../test/haptics';

const handlers = {
  onKeywordChange: jest.fn(), onKeywordSettled: jest.fn(), onTypeFilter: jest.fn(), onOpenFilters: jest.fn(),
};
function show(over: Partial<Parameters<typeof LedgerControls>[0]> = {}) {
  return render(
    <ThemeProvider>
      <LedgerControls
        initialKeyword=""
        settleMs={300}
        searchRef={createRef<TextInputInstance>()}
        typeFilter="ALL"
        filtersOn={0}
        chips={[]}
        stacked={false}
        {...handlers}
        {...over}
      />
    </ThemeProvider>
  );
}
beforeEach(() => jest.clearAllMocks());

describe('LedgerControls', () => {
  it('keeps the search field, its placeholder and its spoken name', () => {
    show({ initialKeyword: 'rent' });
    const field = screen.getByLabelText('Search transactions');
    expect(field.props.value).toBe('rent');
    expect(field.props.placeholder).toBe('Search description, merchant, bank…');
    fireEvent.changeText(field, 'rent 2');
    expect(handlers.onKeywordChange).toHaveBeenCalledWith('rent 2');
  });

  it('hands over the search once typing pauses, once, with what was typed last', () => {
    jest.useFakeTimers();
    show();
    const field = screen.getByLabelText('Search transactions');
    fireEvent.changeText(field, 'r');
    fireEvent.changeText(field, 're');
    jest.advanceTimersByTime(299);
    fireEvent.changeText(field, 'ren');
    jest.advanceTimersByTime(299);
    expect(handlers.onKeywordSettled).not.toHaveBeenCalled();
    expect(handlers.onKeywordChange).toHaveBeenCalledTimes(3);

    jest.advanceTimersByTime(1);
    expect(handlers.onKeywordSettled).toHaveBeenCalledTimes(1);
    expect(handlers.onKeywordSettled).toHaveBeenCalledWith('ren');
    jest.useRealTimers();
  });

  it('hands over an emptied box once the clear button is pressed', () => {
    jest.useFakeTimers();
    show({ initialKeyword: 'rent' });
    fireEvent.press(screen.getByLabelText('Clear search'));
    expect(screen.getByLabelText('Search transactions').props.value).toBe('');
    jest.advanceTimersByTime(300);
    expect(handlers.onKeywordSettled).toHaveBeenCalledWith('');
    jest.useRealTimers();
  });

  it('drops a search still waiting when the controls go away', () => {
    jest.useFakeTimers();
    const view = show();
    fireEvent.changeText(screen.getByLabelText('Search transactions'), 'rent');
    view.unmount();
    jest.advanceTimersByTime(1000);
    expect(handlers.onKeywordSettled).not.toHaveBeenCalled();
    jest.useRealTimers();
  });

  it('offers All, Income and Expense as one switch, saying which is selected', () => {
    show({ typeFilter: 'INCOME' });
    expect(screen.getByLabelText('Filter: income')).toBeSelected();
    expect(screen.getByLabelText('Filter: all')).not.toBeSelected();
    fireEvent.press(screen.getByLabelText('Filter: expense'));
    expect(handlers.onTypeFilter).toHaveBeenCalledWith('EXPENSE');
  });

  it('ticks only when the type filter changes', () => {
    show({ typeFilter: 'ALL' });
    fireEvent.press(screen.getByLabelText('Filter: all'));
    expectHaptics({});
    fireEvent.press(screen.getByLabelText('Filter: income'));
    expectHaptics({ selection: 1 });
  });

  it('keeps each segment label on one line and stops it growing past what a third of the row holds', () => {
    // Seen at accessibility-large on an iPhone 17 Pro simulator: "Expens" / "e".
    show();
    for (const label of ['All', 'Income', 'Expense']) {
      const text = screen.getByText(label);
      expect(text.props.numberOfLines).toBe(1);
      expect(text.props.maxFontSizeMultiplier).toBe(1.5);
    }
  });

  it('gives the search field no fixed line height, so its text stays centred at any text size', () => {
    // Seen at accessibility-large: with the type ramp's line height the text sat at the bottom of
    // the field, cut off.
    show();
    const style = StyleSheet.flatten(screen.getByLabelText('Search transactions').props.style);
    expect(style.lineHeight).toBeUndefined();
    expect(style.fontSize).toBe(14);
  });

  it('opens the filters from one button', () => {
    show();
    fireEvent.press(screen.getByLabelText('Filters'));
    expect(handlers.onOpenFilters).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('filters-count')).toBeNull();
  });

  it('shows and says how many filters are on', () => {
    show({ filtersOn: 2 });
    expect(screen.getByLabelText('Filters, 2 on')).toBeTruthy();
    expect(screen.getByTestId('filters-count')).toHaveTextContent('2');
  });

  it('shows one removable chip per active filter', () => {
    const chips: ActiveFilterChip[] = [
      { key: 'status', label: 'Transfer', onClear: jest.fn() },
      { key: 'international', label: 'International', onClear: jest.fn() },
    ];
    show({ chips });
    expect(screen.getByText('Transfer')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Clear filter: International'));
    expect(chips[1].onClear).toHaveBeenCalledTimes(1);
    expect(chips[0].onClear).not.toHaveBeenCalled();
  });

  it('renders no chip row without chips', () => {
    show();
    expect(screen.queryByTestId('ledger-active-filters')).toBeNull();
  });

  it('stacks the switch and the button at large text', () => {
    show({ stacked: true });
    expect(screen.getByTestId('ledger-controls-row')).toHaveStyle({ flexDirection: 'column' });
  });

  it('puts them on one line otherwise', () => {
    show();
    expect(screen.getByTestId('ledger-controls-row')).toHaveStyle({ flexDirection: 'row' });
  });
});
