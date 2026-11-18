import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { BudgetsScreen } from './BudgetsScreen';
import { budgetsApi, categoriesApi } from '../api/endpoints';
import { currentYearMonth, monthDateRange, monthLabel } from '../lib/format';
import { hapticError, hapticSuccess, hapticWarning } from '../lib/haptics';
import type { Budget } from '../types';
import { AppAlert } from '../lib/appAlert';
import { expectSkeletonNotSpinner, never } from '../test/loadingStates';

jest.mock('../api/endpoints', () => ({
  budgetsApi: { list: jest.fn(), upsert: jest.fn(), remove: jest.fn() },
  categoriesApi: { list: jest.fn() },
}));

// Automocked (no factory) rather than hand-listing exports: BudgetsScreen's category picker
// renders the real OptionPickerModal, which calls hapticSelection too, and a hand-written factory
// silently omitting a function a component actually calls is exactly the bug that happened here
// once already. Automock covers every current AND future export of the module for free.
jest.mock('../lib/haptics');

const api = budgetsApi as jest.Mocked<typeof budgetsApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;

const budgets: Budget[] = [
  { id: 'b-1', categoryId: 'c-1', categoryName: 'Groceries', monthlyLimit: 10000, spentThisMonth: 4000 },
  { id: 'b-2', categoryId: 'c-2', categoryName: 'Dining', monthlyLimit: 5000, spentThisMonth: 6200 },
];

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <BudgetsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

// Every pace figure depends on the day of the month, so tests pin 17 Nov 2026 (day 17 of 30),
// the day the Figma frames were drawn for. Only the clock is faked by setSystemTime's effect on
// Date; queueMicrotask stays real so promises resolve as usual (same combination
// DashboardScreen.test.tsx uses).
function pinDate(d: Date) {
  jest.useFakeTimers({ doNotFake: ['queueMicrotask'] });
  jest.setSystemTime(d);
}

describe('BudgetsScreen', () => {
  beforeEach(() => {
    pinDate(new Date(2026, 10, 17, 10));
    api.list.mockReset().mockResolvedValue(budgets);
    api.upsert.mockReset().mockResolvedValue(budgets[0]);
    api.remove.mockReset().mockResolvedValue({} as never);
    categories.list.mockReset().mockResolvedValue([
      { id: 'c-1', name: 'Groceries', isSystem: true, icon: 'shopping-cart', color: 'green' },
      { id: 'c-2', name: 'Dining', isSystem: true, icon: 'utensils', color: 'orange' },
    ]);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('shows per-day safe amounts and the forecast line on each card', async () => {
    renderScreen();

    expect(await screen.findByText('₹461 / day safe')).toBeTruthy();
    expect(screen.getByText('About ₹7,059 by 30 Nov')).toBeTruthy();
    expect(screen.getByText('₹4,000 of ₹10,000')).toBeTruthy();
  });

  // Overspending is the thing a budget screen exists to surface, and a progress bar alone can only
  // show "full" -- 100% and 124% look identical.
  it('says how far over budget an overspent category is', async () => {
    renderScreen();

    expect(await screen.findByText('₹1,200 over')).toBeTruthy();
    expect(
      screen.getByLabelText('Dining: ₹6,200 spent of ₹5,000. ₹1,200 over budget.')
    ).toBeTruthy();
  });

  it('shows the month at a glance: spent, safe per day and where this pace lands', async () => {
    renderScreen();

    // The ring's centre figure is an AnimatedNumber (a read-only TextInput), hence display value.
    expect(await screen.findByDisplayValue('₹10,200')).toBeTruthy();
    expect(screen.getByText('of ₹15,000')).toBeTruthy();
    expect(screen.getByText('₹369')).toBeTruthy();
    expect(screen.getByText('At this pace you reach ₹18,000 by 30 Nov, ₹3,000 over your budgets.')).toBeTruthy();
    expect(screen.getByLabelText('₹10,200 spent of ₹15,000. ₹369 a day safe for 13 days. At this pace ₹18,000 by 30 Nov.')).toBeTruthy();
  });

  // jest renders at fontScale 2. The ring is a fixed-size graphic, so its centre text may grow only
  // as far as the hole has room for (same rule as DonutChart's centre); uncapped, "₹22,700" was cut
  // to "₹2" on an iPhone Air simulator at the largest text size.
  it('caps the ring centre text so it stays inside the ring at large text', async () => {
    renderScreen();

    expect((await screen.findByDisplayValue('₹10,200')).props.maxFontSizeMultiplier).toBe(1.15);
    expect(screen.getByText('of ₹15,000').props.maxFontSizeMultiplier).toBe(1.3);
    expect(screen.getByText('of ₹15,000').props.numberOfLines).toBe(1);
  });

  // Side by side, "₹816" broke into "₹81" / "6" at the largest text size.
  it('stacks the hero tiles at large text and keeps each figure on one line', async () => {
    renderScreen();

    await screen.findByText('₹369');
    expect(StyleSheet.flatten(screen.getByTestId('hero-tiles').props.style).flexDirection).toBe('column');
    expect(screen.getByText('₹369').props.numberOfLines).toBe(1);
  });

  it('groups cards as Over, Heading over at this pace and On track, problems first', async () => {
    api.list.mockReset().mockResolvedValue([
      ...budgets,
      { id: 'b-3', categoryId: 'c-3', categoryName: 'Fuel', monthlyLimit: 9000, spentThisMonth: 7200 },
    ]);
    renderScreen();

    expect(await screen.findByText('Over · 1')).toBeTruthy();
    expect(screen.getByText('Heading over at this pace · 1')).toBeTruthy();
    expect(screen.getByText('On track · 1')).toBeTruthy();
    const order = screen.getAllByTestId(/^budget-card-/).map((n) => n.props.testID);
    expect(order).toEqual(['budget-card-b-2', 'budget-card-b-3', 'budget-card-b-1']);
  });

  it('hides groups that have no budgets', async () => {
    api.list.mockReset().mockResolvedValue([budgets[0]]);
    renderScreen();

    expect(await screen.findByText('On track · 1')).toBeTruthy();
    expect(screen.queryByText(/^Over ·/)).toBeNull();
    expect(screen.queryByText(/^Heading over at this pace ·/)).toBeNull();
  });

  it('reads Last day on the final day of the month', async () => {
    pinDate(new Date(2026, 10, 30, 10));
    api.list.mockReset().mockResolvedValue([
      { id: 'b-1', categoryId: 'c-1', categoryName: 'Groceries', monthlyLimit: 2800, spentThisMonth: 1000 },
    ]);
    renderScreen();

    // Both the card and the hero's safe-to-spend tile say it, each about its own amount.
    const card = await screen.findByTestId('budget-card-b-1');
    expect(within(card).getByText('Last day · ₹1,800 left')).toBeTruthy();
    expect(screen.queryByText(/\/ day/)).toBeNull();
  });

  // jest renders at fontScale 2 (large text), so this is the large-text layout.
  it('keeps the three-dot button for a long category name at large text', async () => {
    const longName = 'Entertainment and subscriptions for the whole family';
    api.list.mockReset().mockResolvedValue([
      { id: 'b-9', categoryId: 'c-9', categoryName: longName, monthlyLimit: 3000, spentThisMonth: 500 },
    ]);
    renderScreen();

    expect(await screen.findByLabelText(`More actions for ${longName}`)).toBeTruthy();
    expect(screen.getByText(longName).props.numberOfLines).toBeUndefined();
  });

  it('still shows budgets with a neutral icon when categories fail to load', async () => {
    categories.list.mockReset().mockRejectedValue(new Error('categories down'));
    renderScreen();

    expect(await screen.findByText('Groceries')).toBeTruthy();
    expect(screen.queryByText('Could not load budgets.')).toBeNull();
  });

  describe('New budget sheet', () => {
    async function openNewBudget() {
      await screen.findByText('₹461 / day safe');
      fireEvent.press(screen.getByLabelText('New budget'));
      await settle();
    }

    async function pickGroceries() {
      fireEvent.press(screen.getByLabelText('Choose a category'));
      await settle();
      // By testID, not role/name: the category also appears as a budget card, whose accessible
      // name also contains "Groceries".
      fireEvent.press(screen.getByTestId('category-Groceries'));
      await settle();
    }

    it('saves a budget for the category chosen from the picker', async () => {
      renderScreen();
      await openNewBudget();
      await pickGroceries();
      fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
      fireEvent.press(screen.getByText('Save budget'));
      await settle();

      await waitFor(() => expect(api.upsert).toHaveBeenCalledWith('Groceries', 12000));
      expect(hapticSuccess).toHaveBeenCalledTimes(1);
      await waitFor(() => expect(screen.queryByText('Save budget')).toBeNull());
    });

    it('refreshes the Journey card after a budget is saved, since a first budget is a milestone', async () => {
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
      const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
      render(<QueryClientProvider client={queryClient}><BudgetsScreen /></QueryClientProvider>);
      await openNewBudget();
      await pickGroceries();
      fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
      fireEvent.press(screen.getByText('Save budget'));
      await settle();

      await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['timeline'] }));
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['budgets'] });
      expect(invalidate).toHaveBeenCalledWith({ queryKey: ['dashboard-summary'] });
    });

    // Regression: this screen used to open OptionPickerModal, a bare fixed list with no create
    // affordance -- setting up a budget for a category that doesn't exist yet is a real scenario.
    it('offers to create a new category from the budget picker', async () => {
      renderScreen();
      await openNewBudget();
      fireEvent.press(screen.getByLabelText('Choose a category'));
      await settle();

      expect(screen.getByText('New category')).toBeTruthy();
    });

    // A genuine server-side failure gets hapticError, not hapticWarning -- hapticWarning is
    // reserved for the client-side "form isn't complete yet" cases below.
    it('signals an error haptically, not just success or a validation warning, when the save itself fails', async () => {
      api.upsert.mockRejectedValueOnce(new Error('network down'));
      renderScreen();
      await openNewBudget();
      await pickGroceries();
      fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
      fireEvent.press(screen.getByText('Save budget'));
      await settle();

      expect(await screen.findByText('Could not save this budget. Try again.')).toBeTruthy();
      expect(screen.getByText('Save budget')).toBeTruthy();
      expect(hapticError).toHaveBeenCalledTimes(1);
      expect(hapticWarning).not.toHaveBeenCalled();
      expect(hapticSuccess).not.toHaveBeenCalled();
    });

    it('refuses to save without a category', async () => {
      renderScreen();
      await openNewBudget();
      fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
      fireEvent.press(screen.getByText('Save budget'));
      await settle();

      expect(api.upsert).not.toHaveBeenCalled();
      expect(screen.getByText('Pick a category first.')).toBeTruthy();
      expect(hapticWarning).toHaveBeenCalledTimes(1);
    });

    it('refuses a limit that is not a positive number', async () => {
      renderScreen();
      await openNewBudget();
      await pickGroceries();

      for (const bad of ['', '0', '-1', 'abc']) {
        fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), bad);
        fireEvent.press(screen.getByText('Save budget'));
        await settle();
      }

      expect(api.upsert).not.toHaveBeenCalled();
      expect(hapticWarning).toHaveBeenCalledTimes(4);
    });

    it('no longer shows an always-open add form', async () => {
      renderScreen();
      await screen.findByText('₹461 / day safe');

      expect(screen.queryByText('Set Budget')).toBeNull();
      expect(screen.queryByLabelText('Choose a category')).toBeNull();
    });
  });

  describe('three-dot menu and Edit limit sheet', () => {
    async function openMenu(name: string) {
      fireEvent.press(await screen.findByLabelText(`More actions for ${name}`));
      await settle();
    }

    it('the three-dot button opens Edit limit, See transactions and Remove budget', async () => {
      renderScreen();
      await openMenu('Groceries');

      expect(screen.getByLabelText('Edit limit')).toBeTruthy();
      expect(screen.getByLabelText('See transactions')).toBeTruthy();
      expect(screen.getByLabelText('Remove budget')).toBeTruthy();
    });

    it('only one sheet is open at a time when moving from the menu to Edit limit', async () => {
      renderScreen();
      await openMenu('Groceries');
      fireEvent.press(screen.getByLabelText('Edit limit'));
      await settle();

      expect(screen.queryByLabelText('See transactions')).toBeNull();
      expect(screen.getByText('Save limit')).toBeTruthy();
    });

    it('Edit limit saves the new amount and refreshes the list', async () => {
      renderScreen();
      await openMenu('Groceries');
      fireEvent.press(screen.getByLabelText('Edit limit'));
      await settle();

      expect(screen.getByLabelText(/Monthly limit/i).props.value).toBe('10000');
      expect(screen.getByText('₹4,000 spent so far. At ₹10,000 you can spend ₹461 a day for the next 13 days.')).toBeTruthy();
      fireEvent.press(screen.getByLabelText('Add ₹500'));
      expect(screen.getByLabelText(/Monthly limit/i).props.value).toBe('10500');
      expect(screen.getByText('₹4,000 spent so far. At ₹10,500 you can spend ₹500 a day for the next 13 days.')).toBeTruthy();
      fireEvent.press(screen.getByText('Save limit'));
      await settle();

      await waitFor(() => expect(api.upsert).toHaveBeenCalledWith('Groceries', 10500));
      expect(hapticSuccess).toHaveBeenCalledTimes(1);
      await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
      expect(screen.queryByText('Save limit')).toBeNull();
    });

    it('Edit limit says when the typed limit is already used up', async () => {
      renderScreen();
      await openMenu('Groceries');
      fireEvent.press(screen.getByLabelText('Edit limit'));
      await settle();
      fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '3000');

      expect(screen.getByText('₹4,000 spent so far, already more than ₹3,000.')).toBeTruthy();
    });

    it('Edit limit refuses an empty, zero, negative or non-number amount', async () => {
      renderScreen();
      await openMenu('Groceries');
      fireEvent.press(screen.getByLabelText('Edit limit'));
      await settle();

      for (const bad of ['', '0', '-5', 'abc']) {
        fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), bad);
        fireEvent.press(screen.getByText('Save limit'));
        await settle();
      }

      expect(api.upsert).not.toHaveBeenCalled();
      expect(hapticWarning).toHaveBeenCalledTimes(4);
      expect(screen.getByText('Monthly limit must be a number greater than zero.')).toBeTruthy();
    });

    it.each(['12,50', '1,00', '1,000,00'])('Edit limit asks about the commas in %j without saving', async (typed) => {
      renderScreen();
      await openMenu('Groceries');
      fireEvent.press(screen.getByLabelText('Edit limit'));
      await settle();

      fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), typed);
      fireEvent.press(screen.getByText('Save limit'));
      await settle();

      expect(api.upsert).not.toHaveBeenCalled();
      expect(screen.getByText('Check the commas in this amount. For paise, use a point: 12.50.')).toBeTruthy();
    });

    it('a double tap on Save limit sends one request', async () => {
      api.upsert.mockReset().mockReturnValue(new Promise(() => {}));
      renderScreen();
      await openMenu('Groceries');
      fireEvent.press(screen.getByLabelText('Edit limit'));
      await settle();

      // The label turns to "Saving…" after the first press, so hold on to the same button.
      const saveButton = screen.getByText('Save limit');
      fireEvent.press(saveButton);
      fireEvent.press(saveButton);
      await settle();

      expect(api.upsert).toHaveBeenCalledTimes(1);
    });

    it('See transactions opens this month in this category', async () => {
      renderScreen();
      await openMenu('Groceries');
      const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
      navigate.mockClear();
      fireEvent.press(screen.getByLabelText('See transactions'));
      await settle();

      expect(navigate).toHaveBeenCalledWith('Transactions', {
        filters: expect.objectContaining({ categoryId: 'c-1' }),
      });
      expect(screen.queryByLabelText('Edit limit')).toBeNull();
    });
  });

  it('says so plainly when no budgets are set, with no hero', async () => {
    api.list.mockReset().mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText('No budgets set yet. Tap New budget to set one.')).toBeTruthy();
    expect(screen.queryByText('Safe to spend')).toBeNull();
  });

  // The empty state's own action opens the New budget sheet, proven by driving it through a save.
  it('opens a working New budget sheet from the empty state\'s own action', async () => {
    api.list.mockReset().mockResolvedValue([]);
    renderScreen();
    await screen.findByText(/No budgets set yet/);

    fireEvent.press(screen.getByText('Set your first budget'));
    await settle();
    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    fireEvent.press(screen.getByTestId('category-Groceries'));
    await settle();
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Save budget'));

    await waitFor(() => expect(api.upsert).toHaveBeenCalledWith('Groceries', 12000));
  });

  describe('drill-through into the ledger (Track C/C4)', () => {
    it('opens Transactions filtered to this category and the current calendar month', async () => {
      renderScreen();
      await screen.findByText('₹461 / day safe');
      const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
      navigate.mockClear();

      fireEvent.press(screen.getByLabelText(/Groceries: ₹4,000 spent/));

      const month = currentYearMonth();
      const { dateFrom, dateTo } = monthDateRange(month);
      expect(navigate).toHaveBeenCalledWith('Transactions', {
        filters: expect.objectContaining({
          categoryId: 'c-1', dateFrom, dateTo, label: `Groceries · ${monthLabel(month)}`,
        }),
      });
    });
  });
});

describe('skeleton loading', () => {
  it('shows skeleton budget cards on first load, with New budget already usable', async () => {
    api.list.mockReset().mockReturnValue(new Promise(() => {}));
    categories.list.mockReset().mockResolvedValue([{ id: 'c-1', name: 'Groceries', isSystem: true, icon: 'shopping-cart', color: 'green' }]);

    renderScreen();

    // Adding a budget must not wait on the budgets list.
    expect(screen.getAllByTestId('skeleton-budget-card', { hidden: true }).length).toBeGreaterThan(0);
    fireEvent.press(screen.getByLabelText('New budget'));
    await settle();
    expect(screen.getByText('Save budget')).toBeTruthy();
  });
});

// D3 (Track D security cleanup). Budget amounts are as screenshot-attractive as anything on the
// Dashboard or Accounts screen, which already guard against this.
describe('screen capture protection (Track D/D3)', () => {
  it('calls usePreventScreenCapture on mount', () => {
    renderScreen();

    expect(usePreventScreenCapture).toHaveBeenCalled();
  });
});

describe('BudgetsScreen first load', () => {
  it('reads as one "Loading" region to a screen reader during the first load', () => {
    api.list.mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
  });
});

describe('BudgetsScreen — removing a budget', () => {
  beforeEach(() => {
    api.list.mockReset().mockResolvedValue(budgets);
    api.remove.mockReset().mockResolvedValue({} as never);
    categories.list.mockReset().mockResolvedValue([]);
  });

  async function chooseRemove(name: string) {
    fireEvent.press(await screen.findByLabelText(`More actions for ${name}`));
    await settle();
    fireEvent.press(screen.getByLabelText('Remove budget'));
    await settle();
  }

  it('asks first, then removes that budget and refreshes the list', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    await chooseRemove('Dining');
    expect(alertSpy.mock.calls[0][0]).toBe('Remove the Dining budget?');
    expect(alertSpy.mock.calls[0][1]).toBe('Your transactions stay as they are. You can set a budget for this category again at any time.');
    expect(api.remove).not.toHaveBeenCalled();

    const buttons = alertSpy.mock.calls[0][2]!;
    await act(async () => { buttons.find((b) => b.text === 'Remove')!.onPress!(); });

    await waitFor(() => expect(api.remove).toHaveBeenCalledWith('b-2'));
    await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
    expect(hapticSuccess).toHaveBeenCalledTimes(1);
    alertSpy.mockRestore();
  });

  it('a removal the server refuses shows its message', async () => {
    api.remove.mockRejectedValueOnce(new Error('down'));
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    await chooseRemove('Groceries');
    const buttons = alertSpy.mock.calls[0][2]!;
    await act(async () => { buttons.find((b) => b.text === 'Remove')!.onPress!(); });

    expect(await screen.findByText('Could not remove this budget. Try again.')).toBeTruthy();
    expect(hapticError).toHaveBeenCalledTimes(1);
    alertSpy.mockRestore();
  });

  it('pressing the three-dot button and Remove does not also open the transactions', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    await screen.findByLabelText('More actions for Dining');
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();
    await chooseRemove('Dining');

    expect(navigate).not.toHaveBeenCalled();
    expect(screen.queryByLabelText('Remove budget')).toBeNull();
    alertSpy.mockRestore();
  });
});

describe('BudgetsScreen: edge cases', () => {
  beforeEach(() => {
    pinDate(new Date(2026, 10, 17, 10));
    api.list.mockReset().mockResolvedValue(budgets);
    api.upsert.mockReset().mockResolvedValue(budgets[0]);
    api.remove.mockReset().mockResolvedValue({} as never);
    categories.list.mockReset().mockResolvedValue([
      { id: 'c-1', name: 'Groceries', isSystem: true, icon: 'shopping-cart', color: 'green' },
    ]);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  async function openEdit(name: string) {
    fireEvent.press(await screen.findByLabelText(`More actions for ${name}`));
    await settle();
    fireEvent.press(screen.getByLabelText('Edit limit'));
    await settle();
  }

  it('keeps showing the budgets it has when a refresh fails', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    render(<QueryClientProvider client={queryClient}><BudgetsScreen /></QueryClientProvider>);
    await screen.findByText('₹461 / day safe');

    api.list.mockRejectedValueOnce(new Error('offline'));
    await act(async () => { await queryClient.refetchQueries({ queryKey: ['budgets'] }); });
    // React Query tells the screen on a timer, and this file fakes timers.
    await act(async () => { jest.advanceTimersByTime(50); });

    expect(queryClient.getQueryState(['budgets'])?.status).toBe('error');
    expect(screen.getByText('₹461 / day safe')).toBeTruthy();
    expect(screen.queryByText('Could not load budgets.')).toBeNull();
  });

  it('shows the saved limit at once, before the refresh returns', async () => {
    renderScreen();
    await screen.findByText('₹461 / day safe');
    api.list.mockReturnValue(new Promise(() => {}));
    api.upsert.mockResolvedValueOnce({ ...budgets[0], monthlyLimit: 20000 });
    await openEdit('Groceries');
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '20000');
    fireEvent.press(screen.getByText('Save limit'));
    await settle();

    await waitFor(() => expect(screen.getByText('₹4,000 of ₹20,000')).toBeTruthy());
  });

  it('a removed budget leaves the list even if the refresh fails', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    fireEvent.press(await screen.findByLabelText('More actions for Groceries'));
    await settle();
    fireEvent.press(screen.getByLabelText('Remove budget'));
    await settle();
    api.list.mockRejectedValue(new Error('offline'));
    const buttons = alertSpy.mock.calls[0][2]!;
    await act(async () => { buttons.find((b) => b.text === 'Remove')!.onPress!(); });
    await settle();

    await waitFor(() => expect(screen.queryByLabelText('More actions for Groceries')).toBeNull());
    expect(screen.getByLabelText('More actions for Dining')).toBeTruthy();
    alertSpy.mockRestore();
  });

  it('accepts a limit typed with commas, and the chips add to it', async () => {
    renderScreen();
    await openEdit('Groceries');
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '10,000');
    fireEvent.press(screen.getByLabelText('Add ₹500'));
    expect(screen.getByLabelText(/Monthly limit/i).props.value).toBe('10500');

    fireEvent.press(screen.getByText('Save limit'));
    await settle();
    await waitFor(() => expect(api.upsert).toHaveBeenCalledWith('Groceries', 10500));
  });

  it('does not call a limit equal to the spend "already more than" it', async () => {
    renderScreen();
    await openEdit('Groceries');
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '4000');

    expect(screen.getByText('₹4,000 spent so far, all of this limit.')).toBeTruthy();
  });

  it('says 1 day on the day before the last', async () => {
    pinDate(new Date(2026, 10, 29, 10));
    renderScreen();
    await openEdit('Groceries');

    expect(screen.getByText('₹4,000 spent so far. At ₹10,000 you can spend ₹6,000 a day for the next 1 day.')).toBeTruthy();
    expect(screen.getByText(' day')).toBeTruthy();
    expect(screen.queryByText(' days')).toBeNull();
  });

  it('a budget exactly at its limit says Limit reached, not ₹0 over', async () => {
    api.list.mockResolvedValue([{ ...budgets[0], spentThisMonth: 10000 }]);
    renderScreen();

    expect(await screen.findByText('Limit reached')).toBeTruthy();
    expect(screen.queryByText('₹0 over')).toBeNull();
  });

  it('warns before New budget replaces a limit the category already has', async () => {
    renderScreen();
    await screen.findByText('₹461 / day safe');
    fireEvent.press(screen.getByLabelText('New budget'));
    await settle();
    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    fireEvent.press(screen.getByTestId('category-Groceries'));
    await settle();

    expect(screen.getByText('Groceries already has a ₹10,000 budget. Saving replaces that limit.')).toBeTruthy();
  });
});

// At the largest text size on an iPhone Air the keyboard covered Save, the decimal pad has no key
// to close it, and the sheet's title sat under the status bar.
describe('BudgetsScreen: sheets with the keyboard open', () => {
  beforeEach(() => {
    pinDate(new Date(2026, 10, 17, 10));
    api.list.mockReset().mockResolvedValue(budgets);
    categories.list.mockReset().mockResolvedValue([]);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('a sheet scrolls, keeps taps working and lets a drag close the keyboard', async () => {
    renderScreen();
    await screen.findByText('₹461 / day safe');
    fireEvent.press(screen.getByLabelText('New budget'));
    await settle();

    const scroll = screen.getByTestId('budget-sheet-scroll');
    expect(scroll.props.keyboardShouldPersistTaps).toBe('handled');
    expect(scroll.props.keyboardDismissMode).toBe('on-drag');
    expect(within(scroll).getByText('Save budget')).toBeTruthy();
  });
});

// The category picker inside New budget can rename a category, and a card saves its limit by name:
// a stale name would create a second category and a second budget.
describe('BudgetsScreen: closing New budget', () => {
  beforeEach(() => {
    pinDate(new Date(2026, 10, 17, 10));
    api.list.mockReset().mockResolvedValue(budgets);
    categories.list.mockReset().mockResolvedValue([]);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it('refreshes the budgets, so a category renamed in the picker shows its new name', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
    render(<QueryClientProvider client={queryClient}><BudgetsScreen /></QueryClientProvider>);
    await screen.findByText('₹461 / day safe');
    fireEvent.press(screen.getByLabelText('New budget'));
    await settle();
    invalidate.mockClear();

    fireEvent.press(screen.getAllByLabelText('Close').at(-1)!);
    await settle();

    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['budgets'] });
  });
});

describe('BudgetsScreen: limits the server would refuse', () => {
  beforeEach(() => {
    pinDate(new Date(2026, 10, 17, 10));
    api.list.mockReset().mockResolvedValue(budgets);
    api.upsert.mockReset().mockResolvedValue(budgets[0]);
    categories.list.mockReset().mockResolvedValue([]);
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it.each([
    ['100.555', 'Monthly limit can have at most 2 decimal places.'],
    ['1000000000000', 'Monthly limit is too large.'],
  ])('explains %j in plain words without calling the server', async (typed, message) => {
    renderScreen();
    fireEvent.press(await screen.findByLabelText('More actions for Groceries'));
    await settle();
    fireEvent.press(screen.getByLabelText('Edit limit'));
    await settle();
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), typed);
    fireEvent.press(screen.getByText('Save limit'));
    await settle();

    expect(await screen.findByText(message)).toBeTruthy();
    expect(api.upsert).not.toHaveBeenCalled();
  });
});
