import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Budgets from './Budgets';
import { budgetsApi, categoriesApi, type CategoryOption } from '../api/endpoints';
import type { Budget } from '../types';

vi.mock('../api/endpoints', () => ({
  budgetsApi: { list: vi.fn(), upsert: vi.fn(), remove: vi.fn() },
  categoriesApi: { list: vi.fn() },
}));

function budget(overrides: Partial<Budget> = {}): Budget {
  return {
    id: 'b1',
    categoryId: 'c1',
    categoryName: 'Dining',
    monthlyLimit: 5000,
    spentThisMonth: 2000,
    ...overrides,
  } as Budget;
}

function category(overrides: Partial<CategoryOption> = {}): CategoryOption {
  return {
    id: 'c1',
    name: 'Dining',
    isSystem: true,
    icon: 'utensils',
    color: 'orange',
    ...overrides,
  };
}

// The five budgets drawn in Figma, on 17 Nov 2026 (day 17 of 30).
const SAMPLE: Budget[] = [
  budget({ id: 'b-ent', categoryId: 'c5', categoryName: 'Entertainment', spentThisMonth: 600, monthlyLimit: 2000 }),
  budget({ id: 'b-fuel', categoryId: 'c4', categoryName: 'Fuel', spentThisMonth: 2000, monthlyLimit: 4000 }),
  budget({ id: 'b-food', categoryId: 'c2', categoryName: 'Food and dining', spentThisMonth: 7200, monthlyLimit: 9000 }),
  budget({ id: 'b-groc', categoryId: 'c3', categoryName: 'Groceries', spentThisMonth: 4500, monthlyLimit: 8000 }),
  budget({ id: 'b-shop', categoryId: 'c1', categoryName: 'Shopping', spentThisMonth: 8400, monthlyLimit: 6000 }),
];

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <Budgets />
      </QueryClientProvider>
    </MemoryRouter>
  );
}

function rowNames() {
  return screen.getAllByTestId('budget-row').map((r) => within(r).getByTestId('budget-name').textContent);
}

async function openMenu(name: string) {
  await userEvent.click(await screen.findByRole('button', { name: `More actions for ${name}` }));
  return screen.getByRole('menu');
}

describe('Budgets', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 10, 17, 10));
    vi.mocked(categoriesApi.list).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('fetches categories alongside budgets, to look up each budget row\'s icon and color', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryId: 'c1', categoryName: 'Dining' })]);
    vi.mocked(categoriesApi.list).mockResolvedValue([category({ id: 'c1' })]);

    renderPage();

    await waitFor(() => expect(categoriesApi.list).toHaveBeenCalledTimes(1));
  });

  it("renders the budget's category icon using the matched category's color", async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryId: 'c1', categoryName: 'Dining' })]);
    vi.mocked(categoriesApi.list).mockResolvedValue([category({ id: 'c1', icon: 'utensils', color: 'orange' })]);
    renderPage();

    const row = (await screen.findByText('Dining')).closest('[data-testid="budget-row"]');
    expect(row?.querySelector('svg')).toBeTruthy();
  });

  it('shows the month header and the hero for the month so far', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue(SAMPLE);
    renderPage();

    expect(await screen.findByRole('heading', { level: 1, name: 'Budgets' })).toBeInTheDocument();
    expect(screen.getByText('November 2026 · day 17 of 30')).toBeInTheDocument();
    expect(screen.getByText('₹22,700')).toBeInTheDocument();
    expect(screen.getByText('₹11,059 over your budgets')).toBeInTheDocument();
  });

  it('groups budgets as Over, Heading over at this pace and On track, problems first', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue(SAMPLE);
    renderPage();

    await screen.findByText('Over · 1');
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(headings).toEqual(['Over · 1', 'Heading over at this pace · 1', 'On track · 3']);
    expect(rowNames()).toEqual(['Shopping', 'Food and dining', 'Groceries', 'Fuel', 'Entertainment']);
  });

  it('hides groups that have no budgets', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([SAMPLE[3]]);
    renderPage();

    await screen.findByText('On track · 1');
    expect(screen.queryByText(/^Over ·/)).not.toBeInTheDocument();
    expect(screen.queryByText(/^Heading over at this pace ·/)).not.toBeInTheDocument();
  });

  it('shows per-day safe amounts and the forecast line on each row', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue(SAMPLE);
    renderPage();

    const food = (await screen.findByText('Food and dining')).closest('[data-testid="budget-row"]') as HTMLElement;
    expect(within(food).getByText('₹138 / day safe')).toBeInTheDocument();
    expect(within(food).getByText('Heading ₹3,706 over by 30 Nov')).toBeInTheDocument();
    expect(within(food).getByText('₹7,200')).toBeInTheDocument();
    expect(within(food).getByText('₹9,000')).toBeInTheDocument();
    const shop = screen.getByText('Shopping').closest('[data-testid="budget-row"]') as HTMLElement;
    expect(within(shop).getByText('₹2,400 over')).toBeInTheDocument();
    expect(within(shop).getByRole('img', { name: 'Spent 140%, month 57% gone' })).toBeInTheDocument();
  });

  // jsdom has no layout, so these pin the classes the phone-width check in a browser relied on.
  it('keeps the name on its own line and the limit field at 16px on a phone', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryName: 'Groceries' })]);
    renderPage();

    const name = await screen.findByTestId('budget-name');
    expect(name.className).toContain('max-sm:basis-[calc(100%-3rem)]');

    fireEvent.click(screen.getByRole('button', { name: /Edit the Groceries limit/ }));
    expect(screen.getByLabelText('Monthly limit for Groceries').className).toContain('max-sm:text-base');
  });

  it('reads Last day on the final day of the month', async () => {
    vi.setSystemTime(new Date(2026, 10, 30, 10));
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryName: 'Fuel', spentThisMonth: 1000, monthlyLimit: 2800 })]);
    renderPage();

    const row = (await screen.findByText('Fuel')).closest('[data-testid="budget-row"]') as HTMLElement;
    expect(within(row).getByText('Last day · ₹1,800 left')).toBeInTheDocument();
  });

  it('See transactions links to the ledger filtered to the category and this month', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryName: 'Food and dining' })]);
    renderPage();

    const menu = await openMenu('Food and dining');
    expect(within(menu).getByRole('menuitem', { name: 'See transactions' })).toHaveAttribute(
      'href', '/app/transactions?category=Food%20and%20dining&from=2026-11-01&to=2026-11-30',
    );
  });

  it('the row menu closes on Escape and returns focus to its button', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryName: 'Dining' })]);
    renderPage();

    await openMenu('Dining');
    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'More actions for Dining' })).toHaveFocus();
  });

  it('the row menu closes when clicking elsewhere on the page', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryName: 'Dining' })]);
    renderPage();

    await openMenu('Dining');
    await userEvent.click(screen.getByRole('heading', { level: 1, name: 'Budgets' }));

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  describe('editing a limit in place', () => {
    const FOOD = budget({ id: 'b-food', categoryName: 'Food and dining', spentThisMonth: 7200, monthlyLimit: 9000 });

    async function startEdit() {
      await userEvent.click(await screen.findByRole('button', { name: 'Edit the Food and dining limit, now ₹9,000' }));
      return screen.getByLabelText('Monthly limit for Food and dining');
    }

    it('clicking the dotted limit edits it in place and saves the new amount', async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
      vi.mocked(budgetsApi.upsert).mockResolvedValue(FOOD);
      renderPage();

      const input = await startEdit();
      expect(input).toHaveValue('9000');
      await userEvent.click(screen.getByRole('button', { name: 'Add ₹500' }));
      expect(input).toHaveValue('9500');
      await userEvent.click(screen.getByRole('button', { name: 'Save' }));

      await waitFor(() => expect(budgetsApi.upsert).toHaveBeenCalledWith('Food and dining', 9500));
      await waitFor(() => expect(screen.queryByLabelText('Monthly limit for Food and dining')).not.toBeInTheDocument());
    });

    it('Edit limit in the row menu opens the same editor', async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
      renderPage();

      const menu = await openMenu('Food and dining');
      await userEvent.click(within(menu).getByRole('menuitem', { name: 'Edit limit' }));

      expect(screen.getByLabelText('Monthly limit for Food and dining')).toHaveFocus();
    });

    it('Enter saves the typed amount', async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
      vi.mocked(budgetsApi.upsert).mockResolvedValue(FOOD);
      renderPage();

      const input = await startEdit();
      await userEvent.clear(input);
      await userEvent.type(input, '12000{Enter}');

      await waitFor(() => expect(budgetsApi.upsert).toHaveBeenCalledWith('Food and dining', 12000));
    });

    it('Escape cancels an in-place edit without saving', async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
      renderPage();

      const input = await startEdit();
      await userEvent.clear(input);
      await userEvent.type(input, '1{Escape}');

      expect(screen.queryByLabelText('Monthly limit for Food and dining')).not.toBeInTheDocument();
      expect(budgetsApi.upsert).not.toHaveBeenCalled();
      expect(screen.getByRole('button', { name: 'Edit the Food and dining limit, now ₹9,000' })).toBeInTheDocument();
    });

    it.each(['', '0', '-5', 'abc'])('refuses the limit %j without calling the server', async (typed) => {
      vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
      renderPage();

      const input = await startEdit();
      await userEvent.clear(input);
      if (typed) await userEvent.type(input, typed);
      await userEvent.click(screen.getByRole('button', { name: 'Save' }));

      expect(await screen.findByText('Monthly limit must be greater than zero.')).toBeInTheDocument();
      expect(budgetsApi.upsert).not.toHaveBeenCalled();
    });

    it('a double click on Save sends one request', async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
      vi.mocked(budgetsApi.upsert).mockReturnValue(new Promise(() => {}));
      renderPage();

      await startEdit();
      await userEvent.dblClick(screen.getByRole('button', { name: 'Save' }));

      expect(budgetsApi.upsert).toHaveBeenCalledTimes(1);
    });

    it('keeps the list on screen while it reloads after a save', async () => {
      vi.mocked(budgetsApi.list)
        .mockResolvedValueOnce([FOOD])
        .mockReturnValueOnce(new Promise(() => {}));
      vi.mocked(budgetsApi.upsert).mockResolvedValue(FOOD);
      renderPage();

      await startEdit();
      await userEvent.click(screen.getByRole('button', { name: 'Save' }));

      await waitFor(() => expect(budgetsApi.list).toHaveBeenCalledTimes(2));
      expect(screen.getByText('Food and dining')).toBeInTheDocument();
      expect(screen.getByText('Safe to spend')).toBeInTheDocument();
    });

    it("shows the server's message when saving fails and keeps the editor open", async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
      vi.mocked(budgetsApi.upsert).mockRejectedValue({ response: { data: { message: 'Limit too large.' } } });
      renderPage();

      await startEdit();
      await userEvent.click(screen.getByRole('button', { name: 'Save' }));

      expect(await screen.findByText('Limit too large.')).toBeInTheDocument();
      expect(screen.getByLabelText('Monthly limit for Food and dining')).toBeInTheDocument();
    });
  });

  describe('New budget', () => {
    it('saves the category picked in the dialog', async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([]);
      vi.mocked(categoriesApi.list).mockResolvedValue([category({ id: 'c9', name: 'Travel' })]);
      vi.mocked(budgetsApi.upsert).mockResolvedValue(budget());
      renderPage();

      await userEvent.click(await screen.findByRole('button', { name: 'New budget' }));
      const dialog = screen.getByRole('dialog', { name: 'New budget' });
      await userEvent.click(within(dialog).getByRole('combobox'));
      await userEvent.click(await screen.findByRole('option', { name: /Travel/ }));
      await userEvent.type(within(dialog).getByLabelText('Monthly limit'), '3000');
      await userEvent.click(within(dialog).getByRole('button', { name: 'Save budget' }));

      await waitFor(() => expect(budgetsApi.upsert).toHaveBeenCalledWith('Travel', 3000));
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New budget' })).not.toBeInTheDocument());
    });

    it('refuses to save without a category', async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([]);
      renderPage();

      await userEvent.click(await screen.findByRole('button', { name: 'New budget' }));
      const dialog = screen.getByRole('dialog', { name: 'New budget' });
      await userEvent.type(within(dialog).getByLabelText('Monthly limit'), '3000');
      await userEvent.click(within(dialog).getByRole('button', { name: 'Save budget' }));

      expect(await within(dialog).findByText('Pick a category first.')).toBeInTheDocument();
      expect(budgetsApi.upsert).not.toHaveBeenCalled();
    });

    it('Cancel closes the dialog without saving', async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([]);
      renderPage();

      await userEvent.click(await screen.findByRole('button', { name: 'New budget' }));
      await userEvent.click(within(screen.getByRole('dialog', { name: 'New budget' })).getByRole('button', { name: 'Cancel' }));

      expect(screen.queryByRole('dialog', { name: 'New budget' })).not.toBeInTheDocument();
      expect(budgetsApi.upsert).not.toHaveBeenCalled();
    });

    it('no longer shows the old free-text category form', async () => {
      vi.mocked(budgetsApi.list).mockResolvedValue([]);
      renderPage();

      await screen.findByText('No budgets set');
      expect(screen.queryByText('Set a Budget')).not.toBeInTheDocument();
    });
  });

  it('still shows budgets, falling back to a default icon, when categories fail to load', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryId: 'c1', categoryName: 'Dining' })]);
    vi.mocked(categoriesApi.list).mockRejectedValue(new Error('categories service down'));
    renderPage();

    expect(await screen.findByText('Dining')).toBeInTheDocument();
    expect(screen.queryByText('Could not load budgets.')).not.toBeInTheDocument();
  });

  // The actual bug this page had: `budgets` started `[]`, which the render logic couldn't tell
  // apart from "genuinely no budgets set" -- so the EmptyState rendered immediately on every
  // mount, before the fetch had a chance to resolve, then popped to real content once it did.
  it('never shows the empty state while the initial fetch is still in flight', async () => {
    let resolveList: (b: Budget[]) => void;
    vi.mocked(budgetsApi.list).mockReturnValue(
      new Promise((resolve) => {
        resolveList = resolve;
      })
    );

    renderPage();

    expect(screen.queryByText('No budgets set')).not.toBeInTheDocument();

    resolveList!([budget()]);
    await waitFor(() => expect(screen.getByText('Dining')).toBeInTheDocument());
    expect(screen.queryByText('No budgets set')).not.toBeInTheDocument();
  });

  it('shows the empty state, and no hero, once loading finishes with genuinely no budgets', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText('No budgets set')).toBeInTheDocument();
    expect(screen.getByText('Choose New budget to start tracking a category.')).toBeInTheDocument();
    expect(screen.queryByText('Safe to spend')).not.toBeInTheDocument();
  });

  it('renders each budget once loaded', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([
      budget({ id: 'b1', categoryName: 'Dining', monthlyLimit: 5000, spentThisMonth: 2000 }),
      budget({ id: 'b2', categoryName: 'Groceries', monthlyLimit: 8000, spentThisMonth: 8500 }),
    ]);
    renderPage();

    expect(await screen.findByText('Dining')).toBeInTheDocument();
    expect(screen.getByText('Groceries')).toBeInTheDocument();
  });

  it('surfaces a failed load as an error, distinct from the loading state', async () => {
    vi.mocked(budgetsApi.list).mockRejectedValue(new Error('network error'));
    renderPage();

    await waitFor(() => expect(screen.getByText('Could not load budgets.')).toBeInTheDocument());
  });

  it('a failed load does not also claim there are no budgets', async () => {
    vi.mocked(budgetsApi.list).mockRejectedValue(new Error('network error'));
    renderPage();

    await screen.findByText('Could not load budgets.');
    expect(screen.queryByText('No budgets set')).not.toBeInTheDocument();
  });

  it('gives each open limit editor its own field, even for names that differ only in punctuation', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([
      budget({ id: 'b1', categoryName: 'Food & drink', monthlyLimit: 3000, spentThisMonth: 100 }),
      budget({ id: 'b2', categoryName: 'Food - drink', monthlyLimit: 4000, spentThisMonth: 100 }),
    ]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'Edit the Food & drink limit, now ₹3,000' }));
    await userEvent.click(screen.getByRole('button', { name: 'Edit the Food - drink limit, now ₹4,000' }));

    expect(screen.getByLabelText('Monthly limit for Food & drink')).toHaveValue('3000');
    expect(screen.getByLabelText('Monthly limit for Food - drink')).toHaveValue('4000');
  });

  it('removes a budget after the user confirms, then reloads the list', async () => {
    vi.mocked(budgetsApi.list)
      .mockResolvedValueOnce([budget({ id: 'b1', categoryName: 'Dining' })])
      .mockResolvedValueOnce([]);
    vi.mocked(budgetsApi.remove).mockResolvedValue({} as never);
    renderPage();

    const menu = await openMenu('Dining');
    await userEvent.click(within(menu).getByRole('menuitem', { name: 'Remove budget' }));
    expect(screen.getByText('Remove the Dining budget?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(budgetsApi.remove).toHaveBeenCalledWith('b1'));
    expect(await screen.findByText('No budgets set')).toBeInTheDocument();
  });

  it('does not remove anything when the user cancels', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ id: 'b1', categoryName: 'Dining' })]);
    renderPage();

    const menu = await openMenu('Dining');
    await userEvent.click(within(menu).getByRole('menuitem', { name: 'Remove budget' }));
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(budgetsApi.remove).not.toHaveBeenCalled();
    expect(screen.getAllByTestId('budget-row')).toHaveLength(1);
  });

  it('shows the server\'s message when removing fails', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ id: 'b1', categoryName: 'Dining' })]);
    vi.mocked(budgetsApi.remove).mockRejectedValue({ response: { data: { message: 'No such budget.' } } });
    renderPage();

    const menu = await openMenu('Dining');
    await userEvent.click(within(menu).getByRole('menuitem', { name: 'Remove budget' }));
    await userEvent.click(screen.getByRole('button', { name: 'Remove' }));

    expect(await screen.findByText('No such budget.')).toBeInTheDocument();
  });
});

describe('Budgets: edge cases', () => {
  const FOOD = budget({ id: 'b-food', categoryId: 'c2', categoryName: 'Food and dining', spentThisMonth: 2000, monthlyLimit: 9000 });
  const limitButton = (amount: string) => screen.findByRole('button', { name: `Edit the Food and dining limit, now ${amount}` });

  function deferred<T>() {
    let resolve!: (value: T) => void;
    const promise = new Promise<T>((r) => { resolve = r; });
    return { promise, resolve };
  }

  beforeEach(() => {
    vi.resetAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2026, 10, 17, 10));
    vi.mocked(categoriesApi.list).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the saved limit at once, before the reload returns', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValueOnce([FOOD]).mockReturnValueOnce(new Promise(() => {}));
    vi.mocked(budgetsApi.upsert).mockResolvedValue({ ...FOOD, monthlyLimit: 12000 });
    renderPage();

    await userEvent.click(await limitButton('₹9,000'));
    const input = screen.getByLabelText('Monthly limit for Food and dining');
    await userEvent.clear(input);
    await userEvent.type(input, '12000{Enter}');

    expect(await limitButton('₹12,000')).toBeInTheDocument();
  });

  it('an older reload arriving late does not undo a newer one', async () => {
    const first = deferred<Budget[]>();
    vi.mocked(budgetsApi.list)
      .mockResolvedValueOnce([FOOD])
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce([{ ...FOOD, monthlyLimit: 15000 }]);
    vi.mocked(budgetsApi.upsert)
      .mockResolvedValueOnce({ ...FOOD, monthlyLimit: 12000 })
      .mockResolvedValueOnce({ ...FOOD, monthlyLimit: 15000 });
    renderPage();

    for (const [from, to] of [['₹9,000', '12000'], ['₹12,000', '15000']]) {
      await userEvent.click(await limitButton(from));
      const input = screen.getByLabelText('Monthly limit for Food and dining');
      await userEvent.clear(input);
      await userEvent.type(input, `${to}{Enter}`);
    }
    await limitButton('₹15,000');
    first.resolve([{ ...FOOD, monthlyLimit: 12000 }]);
    await new Promise((r) => setTimeout(r, 0));

    expect(await limitButton('₹15,000')).toBeInTheDocument();
  });

  it('accepts a limit typed with commas, and the chips add to it', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    vi.mocked(budgetsApi.upsert).mockResolvedValue(FOOD);
    renderPage();

    await userEvent.click(await limitButton('₹9,000'));
    const input = screen.getByLabelText('Monthly limit for Food and dining');
    await userEvent.clear(input);
    await userEvent.type(input, '10,000');
    await userEvent.click(screen.getByRole('button', { name: 'Add ₹500' }));
    expect(input).toHaveValue('10500');

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(budgetsApi.upsert).toHaveBeenCalledWith('Food and dining', 10500));
  });

  it('Escape does not pretend to cancel a save already on its way', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    vi.mocked(budgetsApi.upsert).mockReturnValue(new Promise(() => {}));
    renderPage();

    await userEvent.click(await limitButton('₹9,000'));
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await userEvent.type(screen.getByLabelText('Monthly limit for Food and dining'), '{Escape}');

    expect(screen.getByLabelText('Monthly limit for Food and dining')).toBeInTheDocument();
  });

  it('puts focus back on the limit after Escape', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    renderPage();

    await userEvent.click(await limitButton('₹9,000'));
    await userEvent.keyboard('{Escape}');

    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit the Food and dining limit, now ₹9,000' })).toHaveFocus());
  });

  it('puts focus on the limit after a save that moves the budget to another group', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValueOnce([FOOD]).mockReturnValue(new Promise(() => {}));
    vi.mocked(budgetsApi.upsert).mockResolvedValue({ ...FOOD, monthlyLimit: 2000 });
    renderPage();

    await userEvent.click(await limitButton('₹9,000'));
    const input = screen.getByLabelText('Monthly limit for Food and dining');
    await userEvent.clear(input);
    await userEvent.type(input, '2000{Enter}');

    expect(await screen.findByRole('heading', { name: 'Over · 1' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit the Food and dining limit, now ₹2,000' })).toHaveFocus());
  });

  it('cancelling Remove puts focus back on the row menu', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    renderPage();

    const menu = await openMenu('Food and dining');
    await userEvent.click(within(menu).getByRole('menuitem', { name: 'Remove budget' }));
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'More actions for Food and dining' })).toHaveFocus());
  });

  it('locks a row while it is being removed, so it cannot be removed twice', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    vi.mocked(budgetsApi.remove).mockReturnValue(new Promise(() => {}));
    renderPage();

    const menu = await openMenu('Food and dining');
    await userEvent.click(within(menu).getByRole('menuitem', { name: 'Remove budget' }));
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'More actions for Food and dining' })).toBeDisabled());
    expect(await limitButton('₹9,000')).toBeDisabled();
  });

  it('a removed budget leaves the list even if the reload fails', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValueOnce([FOOD]).mockRejectedValueOnce(new Error('down'));
    vi.mocked(budgetsApi.remove).mockResolvedValue({} as never);
    renderPage();

    const menu = await openMenu('Food and dining');
    await userEvent.click(within(menu).getByRole('menuitem', { name: 'Remove budget' }));
    await userEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Remove' }));

    await waitFor(() => expect(screen.queryByTestId('budget-row')).not.toBeInTheDocument());
  });

  it('warns before New budget replaces a limit the category already has', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    vi.mocked(categoriesApi.list).mockResolvedValue([category({ id: 'c2', name: 'Food and dining' })]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'New budget' }));
    const dialog = screen.getByRole('dialog', { name: 'New budget' });
    await userEvent.click(within(dialog).getByRole('combobox'));
    await userEvent.click(await screen.findByRole('option', { name: /Food and dining/ }));

    expect(within(dialog).getByText('Food and dining already has a ₹9,000 budget. Saving replaces that limit.')).toBeInTheDocument();
  });

  it('reloads after New budget closes, since a category can be renamed inside it', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'New budget' }));
    await userEvent.click(within(screen.getByRole('dialog', { name: 'New budget' })).getByRole('button', { name: 'Cancel' }));

    await waitFor(() => expect(budgetsApi.list).toHaveBeenCalledTimes(2));
  });

  it('refreshes when the page is opened again on a later day', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    renderPage();
    await limitButton('₹9,000');

    document.dispatchEvent(new Event('visibilitychange'));
    expect(budgetsApi.list).toHaveBeenCalledTimes(1);

    vi.setSystemTime(new Date(2026, 11, 1, 9));
    document.dispatchEvent(new Event('visibilitychange'));

    await waitFor(() => expect(budgetsApi.list).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('December 2026 · day 1 of 31')).toBeInTheDocument();
  });

  it('a budget exactly at its limit says Limit reached, not ₹0 over', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([{ ...FOOD, spentThisMonth: 9000 }]);
    renderPage();

    expect(await screen.findByText('Limit reached')).toBeInTheDocument();
    expect(screen.queryByText('₹0 over')).not.toBeInTheDocument();
  });

  it('says 1 day left on the day before the last', async () => {
    vi.setSystemTime(new Date(2026, 10, 29, 10));
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    renderPage();

    expect(await screen.findByText('1 day left · across all budgets')).toBeInTheDocument();
  });

  it('caps a runaway percentage in the ring', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([{ ...FOOD, spentThisMonth: 500000, monthlyLimit: 10 }]);
    renderPage();

    expect(await screen.findByText('999%+')).toBeInTheDocument();
  });

  it('group headings follow the page title in the outline', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
    renderPage();

    expect(await screen.findByRole('heading', { level: 2, name: 'On track · 1' })).toBeInTheDocument();
  });
});

describe('Budgets: limits the server would refuse', () => {
  const FOOD = budget({ id: 'b-food', categoryId: 'c2', categoryName: 'Food and dining', spentThisMonth: 2000, monthlyLimit: 9000 });

  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(categoriesApi.list).mockResolvedValue([]);
    vi.mocked(budgetsApi.list).mockResolvedValue([FOOD]);
  });

  it.each([
    ['100.555', 'Monthly limit can have at most 2 decimal places.'],
    ['1000000000000', 'Monthly limit is too large.'],
  ])('explains %j in plain words without calling the server', async (typed, message) => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: /Edit the Food and dining limit/ }));
    const input = screen.getByLabelText('Monthly limit for Food and dining');
    await userEvent.clear(input);
    await userEvent.type(input, `${typed}{Enter}`);

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(budgetsApi.upsert).not.toHaveBeenCalled();
  });
});
