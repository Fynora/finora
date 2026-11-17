import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
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

  it('saves a budget for the category chosen from the picker', async () => {
    renderScreen();
    await screen.findByText('₹461 / day safe');

    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    // By testID, not role/name: the category also appears as a budget row below, which is now
    // ALSO a button whose accessible name (Track C/C4's drill-through label) CONTAINS "Groceries"
    // as a substring -- getByRole's name match isn't exact, so it matched both.
    fireEvent.press(screen.getByTestId('category-Groceries'));
    await settle();
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Set Budget'));
    await settle();

    await waitFor(() => expect(api.upsert).toHaveBeenCalledWith('Groceries', 12000));
    expect(hapticSuccess).toHaveBeenCalledTimes(1);
  });

  it('refreshes the Journey card after a budget is saved, since a first budget is a milestone', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
    render(<QueryClientProvider client={queryClient}><BudgetsScreen /></QueryClientProvider>);
    await screen.findByText('₹461 / day safe');

    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    fireEvent.press(screen.getByTestId('category-Groceries'));
    await settle();
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Set Budget'));
    await settle();

    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['timeline'] }));
  });

  // Regression: this screen used to open OptionPickerModal, a bare fixed list with no create
  // affordance -- setting up a budget for a category that doesn't exist yet is a real scenario,
  // and it was unreachable here.
  it('offers to create a new category from the budget picker', async () => {
    renderScreen();
    await screen.findByText('₹461 / day safe');

    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();

    expect(screen.getByText('New category')).toBeTruthy();
  });

  // A genuine server-side failure gets hapticError, not hapticWarning -- hapticWarning is
  // reserved for the client-side "form isn't complete yet" cases covered below, a different
  // outcome from "the form was fine and the server rejected it anyway."
  it('signals an error haptically, not just success or a validation warning, when the save itself fails', async () => {
    api.upsert.mockRejectedValueOnce(new Error('network down'));
    renderScreen();
    await screen.findByText('₹461 / day safe');

    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    fireEvent.press(screen.getByTestId('category-Groceries'));
    await settle();
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Set Budget'));
    await settle();

    expect(await screen.findByText('Could not save this budget. Try again.')).toBeTruthy();
    expect(hapticError).toHaveBeenCalledTimes(1);
    expect(hapticWarning).not.toHaveBeenCalled();
    expect(hapticSuccess).not.toHaveBeenCalled();
  });

  // The web page takes the category as free text, so a typo silently creates a budget nothing is
  // ever filed under. Picking from the real category list is what prevents that.
  it('refuses to save without a category', async () => {
    renderScreen();
    await screen.findByText('₹461 / day safe');

    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Set Budget'));
    await settle();

    expect(api.upsert).not.toHaveBeenCalled();
    expect(screen.getByText('Pick a category first.')).toBeTruthy();
    expect(hapticWarning).toHaveBeenCalledTimes(1);
  });

  it('refuses a limit that is not a positive number', async () => {
    renderScreen();
    await screen.findByText('₹461 / day safe');

    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    // By testID, not role/name: the category also appears as a budget row below, which is now
    // ALSO a button whose accessible name (Track C/C4's drill-through label) CONTAINS "Groceries"
    // as a substring -- getByRole's name match isn't exact, so it matched both.
    fireEvent.press(screen.getByTestId('category-Groceries'));
    await settle();

    for (const bad of ['0', '-1', 'abc']) {
      fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), bad);
      fireEvent.press(screen.getByText('Set Budget'));
      await settle();
    }

    expect(api.upsert).not.toHaveBeenCalled();
    expect(hapticWarning).toHaveBeenCalledTimes(3);
  });

  it('says so plainly when no budgets are set, with no hero', async () => {
    api.list.mockReset().mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText('No budgets set yet. Tap New budget to set one.')).toBeTruthy();
    expect(screen.queryByText('Safe to spend')).toBeNull();
  });

  // Phase 5 (Low-Priority Polish). The empty state used to say "Set one above" with nothing to
  // tap. Its own action opens the SAME category picker "Choose a category" already does (worded
  // distinctly, though, so the two don't collide as two identically-labeled controls once both
  // are on screen at once -- an empty list keeps the always-visible add-form Card mounted above
  // it). Proven by driving the picker it opens all the way through a real save, the same way the
  // "saves a budget..." test above proves the pre-existing trigger opens a working picker.
  it('opens a working category picker from the empty state\'s own action', async () => {
    api.list.mockReset().mockResolvedValue([]);
    renderScreen();
    await screen.findByText(/No budgets set yet/);

    fireEvent.press(screen.getByText('Set your first budget'));
    await settle();
    fireEvent.press(screen.getByTestId('category-Groceries'));
    await settle();
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Set Budget'));

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
  it('shows skeleton budget cards on first load, with the add-budget form already usable', () => {
    api.list.mockReset().mockReturnValue(new Promise(() => {}));
    categories.list.mockReset().mockResolvedValue([{ id: 'c-1', name: 'Groceries', isSystem: true, icon: 'shopping-cart', color: 'green' }]);

    renderScreen();

    // The shell -- category picker and Set Budget button -- must not wait on the budgets list.
    expect(screen.getByLabelText('Choose a category')).toBeTruthy();
    expect(screen.getByText('Set Budget')).toBeTruthy();
    expect(screen.getAllByTestId('skeleton-budget-card', { hidden: true }).length).toBeGreaterThan(0);
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

  it('asks first, then removes that budget and refreshes the list', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    fireEvent.press(await screen.findByLabelText('More actions for Dining'));
    expect(alertSpy.mock.calls[0][0]).toBe('Remove the Dining budget?');
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
    fireEvent.press(await screen.findByLabelText('More actions for Groceries'));
    const buttons = alertSpy.mock.calls[0][2]!;
    await act(async () => { buttons.find((b) => b.text === 'Remove')!.onPress!(); });

    expect(await screen.findByText('Could not remove this budget. Try again.')).toBeTruthy();
    expect(hapticError).toHaveBeenCalledTimes(1);
    alertSpy.mockRestore();
  });

  it('pressing Remove does not also open the transactions', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    const removeButton = await screen.findByLabelText('More actions for Dining');
    const { navigate } = useNavigation<never>() as unknown as { navigate: jest.Mock };
    navigate.mockClear();
    fireEvent.press(removeButton);

    expect(navigate).not.toHaveBeenCalled();
    alertSpy.mockRestore();
  });
});
