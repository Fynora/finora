import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CategoryDeleteSheet } from './CategoryDeleteSheet';
import { categoriesApi, type CategoryOption, type CategoryUsage } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  categoriesApi: { usage: jest.fn(), delete: jest.fn(), list: jest.fn(), options: jest.fn() },
}));

const api = categoriesApi as jest.Mocked<typeof categoriesApi>;

const CATEGORY: CategoryOption = { id: 'c-1', name: 'Dining', isSystem: false, icon: 'utensils', color: 'orange' };
const OTHER: CategoryOption = { id: 'c-2', name: 'Groceries', isSystem: false, icon: 'shopping-cart', color: 'green' };

const onClose = jest.fn();
const onDeleted = jest.fn();

function renderSheet(category: CategoryOption = CATEGORY) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <CategoryDeleteSheet category={category} onClose={onClose} onDeleted={onDeleted} />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

function usage(over: Partial<CategoryUsage> = {}): CategoryUsage {
  return { transactionCount: 0, hasBudget: false, ruleCount: 0, learningRowCount: 0, ...over };
}

beforeEach(() => {
  onClose.mockReset();
  onDeleted.mockReset();
  api.usage.mockReset();
  api.delete.mockReset();
  api.list.mockReset().mockResolvedValue([CATEGORY, OTHER]);
  api.options.mockReset().mockResolvedValue({ icons: [], colors: [] });
});

describe('CategoryDeleteSheet', () => {
  it('deletes immediately, with no reassignment, when nothing depends on the category', async () => {
    api.usage.mockResolvedValue(usage());
    api.delete.mockResolvedValue(undefined as never);
    renderSheet();
    await waitFor(() => expect(api.usage).toHaveBeenCalledWith('c-1'));
    await settle();

    expect(
      screen.getByRole('button', { name: /^Delete$/ }).props.accessibilityState.disabled
    ).toBe(false);

    fireEvent.press(screen.getByRole('button', { name: /^Delete$/ }));
    await settle();

    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('c-1', undefined));
    expect(onDeleted).toHaveBeenCalled();
  });

  it('shows what a delete would touch', async () => {
    api.usage.mockResolvedValue(usage({ transactionCount: 12, hasBudget: true, ruleCount: 2, learningRowCount: 5 }));
    renderSheet();
    await settle();

    expect(await screen.findByText('12 transactions')).toBeTruthy();
    expect(screen.getByText('1 budget')).toBeTruthy();
    expect(screen.getByText('2 rules')).toBeTruthy();
    expect(screen.getByText('5 learned merchants')).toBeTruthy();
  });

  it('requires a reassignment target before Delete is enabled when anything depends on it', async () => {
    api.usage.mockResolvedValue(usage({ transactionCount: 3 }));
    renderSheet();
    await settle();

    await screen.findByText('Move everything to');
    expect(
      screen.getByRole('button', { name: /^Delete$/ }).props.accessibilityState.disabled
    ).toBe(true);

    fireEvent.press(screen.getByText('Choose a category…'));
    await settle();
    fireEvent.press(await screen.findByTestId('category-Groceries'));
    await settle();

    expect(screen.getByText('Groceries')).toBeTruthy();
    expect(
      screen.getByRole('button', { name: /^Delete$/ }).props.accessibilityState.disabled
    ).toBe(false);

    fireEvent.press(screen.getByRole('button', { name: /^Delete$/ }));
    await settle();

    await waitFor(() => expect(api.delete).toHaveBeenCalledWith('c-1', 'c-2'));
  });

  it('excludes the category being deleted from its own reassignment picker', async () => {
    api.usage.mockResolvedValue(usage({ transactionCount: 3 }));
    renderSheet();
    await settle();
    await screen.findByText('Move everything to');

    fireEvent.press(screen.getByText('Choose a category…'));
    await settle();

    expect(await screen.findByTestId('category-Groceries')).toBeTruthy();
    expect(screen.queryByTestId('category-Dining')).toBeNull();
  });

  it('shows a warning, not a silent forever-disabled Delete, when the usage check fails', async () => {
    api.usage.mockRejectedValue(new Error('network'));
    renderSheet();
    await settle();

    expect(await screen.findByText(/Couldn.t check what this category is used for/)).toBeTruthy();
    expect(
      screen.getByRole('button', { name: /^Delete$/ }).props.accessibilityState.disabled
    ).toBe(true);
  });

  it('shows an error and does not close when the delete request fails', async () => {
    api.usage.mockResolvedValue(usage());
    api.delete.mockRejectedValue(
      Object.assign(new Error('bad'), { isAxiosError: true, response: { status: 400, data: { message: 'This category still has dependents.' } } })
    );
    renderSheet();
    await waitFor(() => expect(api.usage).toHaveBeenCalled());
    await settle();

    fireEvent.press(screen.getByRole('button', { name: /^Delete$/ }));
    await settle();

    expect(await screen.findByText('This category still has dependents.')).toBeTruthy();
    expect(onDeleted).not.toHaveBeenCalled();
  });
});
