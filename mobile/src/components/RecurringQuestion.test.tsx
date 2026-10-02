import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RecurringQuestion } from './RecurringQuestion';
import { categoriesApi, recurringApi } from '../api/endpoints';
import { invalidateFinancialData } from '../lib/invalidateFinancialData';

jest.mock('../api/endpoints', () => ({
  recurringApi: { categorize: jest.fn() },
  categoriesApi: { list: jest.fn() },
}));
jest.mock('../lib/invalidateFinancialData', () => ({ invalidateFinancialData: jest.fn() }));
// The real picker is a full sheet with its own tests; a stand-in that picks one category is enough here.
jest.mock('./CategoryPickerModal', () => {
  const { Pressable: MockPressable, Text: MockText } = jest.requireActual('react-native');
  return {
    CategoryPickerModal: ({ visible, onSelect }: { visible: boolean; onSelect: (c: { name: string }) => void }) =>
      visible ? (
        <MockPressable accessibilityRole="button" onPress={() => onSelect({ name: 'Dining' })}>
          <MockText>Pick Dining</MockText>
        </MockPressable>
      ) : null,
  };
});

const ALL = ['Rent', 'Loan EMI', 'Subscriptions', 'Education', 'Insurance', 'Utilities', 'Investments', 'Dining'];
const categories = (names: string[]) =>
  names.map((name, i) => ({ id: `cat-${i}`, name, isSystem: true, icon: 'tag', color: 'gray' }));

function renderQuestion(props: Partial<React.ComponentProps<typeof RecurringQuestion>> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <RecurringQuestion merchant="sample owner" state="NEEDS_ANSWER" answer={null} amount={10000} {...props} />
    </QueryClientProvider>,
  );
}

describe('RecurringQuestion', () => {
  beforeEach(() => {
    jest.mocked(recurringApi.categorize).mockReset();
    jest.mocked(invalidateFinancialData).mockReset();
    jest.mocked(categoriesApi.list).mockResolvedValue(categories(ALL) as never);
  });

  it('asks what an unanswered payment is, with a chip per short-list category', async () => {
    renderQuestion();

    expect(screen.getByText('What is this ₹10,000 monthly payment?')).toBeOnTheScreen();
    for (const name of ['Rent', 'Loan EMI', 'Subscriptions', 'Education', 'Insurance', 'Utilities', 'Investments']) {
      expect(await screen.findByRole('button', { name })).toBeOnTheScreen();
    }
    expect(screen.getByRole('button', { name: 'Something else' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Dining' })).not.toBeOnTheScreen();
  });

  it('hides a chip for a category the user no longer has', async () => {
    jest.mocked(categoriesApi.list).mockResolvedValue(categories(ALL.filter((n) => n !== 'Rent')) as never);
    renderQuestion();

    expect(await screen.findByRole('button', { name: 'Loan EMI' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Rent' })).not.toBeOnTheScreen();
  });

  it('saves a chip once and refreshes the financial data', async () => {
    jest.mocked(recurringApi.categorize).mockResolvedValue(undefined as never);
    renderQuestion();

    fireEvent.press(await screen.findByRole('button', { name: 'Rent' }));

    await waitFor(() => expect(invalidateFinancialData).toHaveBeenCalledTimes(1));
    expect(recurringApi.categorize).toHaveBeenCalledTimes(1);
    expect(recurringApi.categorize).toHaveBeenCalledWith('sample owner', 'Rent');
  });

  it('disables the chips while a save is in flight', async () => {
    let resolve: () => void = () => {};
    jest.mocked(recurringApi.categorize).mockReturnValue(new Promise<void>((r) => { resolve = r; }) as never);
    renderQuestion();

    fireEvent.press(await screen.findByRole('button', { name: 'Rent' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Loan EMI' })).toBeDisabled());
    await act(async () => { resolve(); });
  });

  it('"Something else" picks from the full category list', async () => {
    jest.mocked(recurringApi.categorize).mockResolvedValue(undefined as never);
    renderQuestion();

    fireEvent.press(await screen.findByRole('button', { name: 'Something else' }));
    fireEvent.press(screen.getByRole('button', { name: 'Pick Dining' }));

    await waitFor(() => expect(recurringApi.categorize).toHaveBeenCalledWith('sample owner', 'Dining'));
  });

  it('"Something else" closes the picker once a category is chosen, so an error is not hidden behind it', async () => {
    jest.mocked(recurringApi.categorize).mockRejectedValue(new Error('offline'));
    renderQuestion();

    fireEvent.press(await screen.findByRole('button', { name: 'Something else' }));
    fireEvent.press(screen.getByRole('button', { name: 'Pick Dining' }));

    expect(screen.queryByRole('button', { name: 'Pick Dining' })).not.toBeOnTheScreen();
    expect(await screen.findByText("Couldn't save — try again.")).toBeOnTheScreen();
  });

  it('Change can be cancelled, back to the saved answer', async () => {
    renderQuestion({ state: 'ANSWERED', answer: 'Rent' });

    fireEvent.press(screen.getByRole('button', { name: 'Change' }));
    fireEvent.press(await screen.findByRole('button', { name: 'Cancel' }));

    expect(screen.getByText('Rent')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Loan EMI' })).not.toBeOnTheScreen();
  });

  it('a first question has nothing to cancel', async () => {
    renderQuestion();

    expect(await screen.findByRole('button', { name: 'Rent' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeOnTheScreen();
  });

  it('shows the answer with a way to change it', async () => {
    renderQuestion({ state: 'ANSWERED', answer: 'Rent' });

    expect(screen.getByText('Rent')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Change' }));
    expect(await screen.findByRole('button', { name: 'Loan EMI' })).toBeOnTheScreen();
  });

  it('asks again when the amount moved out of the saved range', async () => {
    jest.mocked(recurringApi.categorize).mockResolvedValue(undefined as never);
    renderQuestion({ state: 'AMOUNT_CHANGED', answer: 'Rent', amount: 12500 });

    expect(screen.getByText('₹12,500 to sample owner — still Rent?')).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Yes' }));
    await waitFor(() => expect(recurringApi.categorize).toHaveBeenCalledWith('sample owner', 'Rent'));
  });

  it('renders nothing when there is nothing to ask', () => {
    renderQuestion({ state: 'NONE' });

    expect(screen.queryByText(/What is this/)).not.toBeOnTheScreen();
    expect(screen.queryByRole('button')).not.toBeOnTheScreen();
  });
});
