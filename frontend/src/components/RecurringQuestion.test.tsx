import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RecurringQuestion } from './RecurringQuestion';
import { recurringApi, categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  recurringApi: { categorize: vi.fn() },
  categoriesApi: { list: vi.fn(), options: vi.fn(), create: vi.fn() },
}));

const ALL = ['Rent', 'Loan EMI', 'Subscriptions', 'Education', 'Insurance', 'Utilities', 'Investments', 'Dining'];

function categories(names: string[]) {
  return names.map((name, i) => ({ id: `cat-${i}`, name, isSystem: true, icon: 'tag', color: 'gray' }));
}

function renderQuestion(props: Partial<Parameters<typeof RecurringQuestion>[0]> = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(queryClient, 'invalidateQueries');
  render(
    <QueryClientProvider client={queryClient}>
      <RecurringQuestion merchant="sample owner" state="NEEDS_ANSWER" answer={null} amount={10000} {...props} />
    </QueryClientProvider>,
  );
  return { invalidate };
}

describe('RecurringQuestion', () => {
  beforeEach(() => {
    vi.mocked(recurringApi.categorize).mockReset();
    vi.mocked(categoriesApi.list).mockResolvedValue(categories(ALL) as any);
    vi.mocked(categoriesApi.options).mockResolvedValue({ icons: [], colors: [] } as any);
  });

  it('asks what an unanswered payment is, with a chip per short-list category', async () => {
    renderQuestion();

    expect(screen.getByText('What is this ₹10,000 monthly payment?')).toBeInTheDocument();
    for (const name of ['Rent', 'Loan EMI', 'Subscriptions', 'Education', 'Insurance', 'Utilities', 'Investments']) {
      expect(await screen.findByRole('button', { name })).toBeInTheDocument();
    }
    expect(screen.getByRole('button', { name: 'Something else' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Dining' })).not.toBeInTheDocument();
  });

  it('hides a chip for a category the user no longer has', async () => {
    vi.mocked(categoriesApi.list).mockResolvedValue(categories(ALL.filter((n) => n !== 'Rent')) as any);
    renderQuestion();

    expect(await screen.findByRole('button', { name: 'Loan EMI' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Rent' })).not.toBeInTheDocument();
  });

  it('saves a chip once and refreshes the recurring list and money figures', async () => {
    vi.mocked(recurringApi.categorize).mockResolvedValue(undefined as any);
    const { invalidate } = renderQuestion();

    await userEvent.click(await screen.findByRole('button', { name: 'Rent' }));

    await waitFor(() => expect(recurringApi.categorize).toHaveBeenCalledTimes(1));
    expect(recurringApi.categorize).toHaveBeenCalledWith('sample owner', 'Rent');
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['recurring'] }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['recurring-changed-amounts'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['transactions'] });
  });

  it('disables the chips while a save is in flight', async () => {
    let resolve: () => void = () => {};
    vi.mocked(recurringApi.categorize).mockReturnValue(new Promise<void>((r) => { resolve = r; }) as any);
    renderQuestion();

    await userEvent.click(await screen.findByRole('button', { name: 'Rent' }));

    expect(screen.getByRole('button', { name: 'Loan EMI' })).toBeDisabled();
    resolve();
  });

  it('"Something else" opens the category picker, with Save off until a category is picked', async () => {
    renderQuestion();

    await userEvent.click(await screen.findByRole('button', { name: 'Something else' }));

    expect(screen.getByRole('combobox')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('shows the answer with a way to change it', async () => {
    renderQuestion({ state: 'ANSWERED', answer: 'Rent' });

    expect(screen.getByText('Rent')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Change' }));
    expect(await screen.findByRole('button', { name: 'Loan EMI' })).toBeInTheDocument();
  });

  it('Change can be cancelled, back to the saved answer', async () => {
    renderQuestion({ state: 'ANSWERED', answer: 'Rent' });

    await userEvent.click(screen.getByRole('button', { name: 'Change' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

    expect(screen.getByText('Rent')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Loan EMI' })).not.toBeInTheDocument();
  });

  it('Change on "still X?" can be cancelled, back to the question', async () => {
    renderQuestion({ state: 'AMOUNT_CHANGED', answer: 'Rent', amount: 12500 });

    await userEvent.click(screen.getByRole('button', { name: 'Change' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

    expect(screen.getByText('₹12,500 to sample owner — still Rent?')).toBeInTheDocument();
  });

  it('a first question has nothing to cancel', async () => {
    renderQuestion();

    expect(await screen.findByRole('button', { name: 'Rent' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
  });

  it('asks again when the amount moved out of the saved range', async () => {
    vi.mocked(recurringApi.categorize).mockResolvedValue(undefined as any);
    renderQuestion({ state: 'AMOUNT_CHANGED', answer: 'Rent', amount: 12500 });

    expect(screen.getByText('₹12,500 to sample owner — still Rent?')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Yes' }));
    await waitFor(() => expect(recurringApi.categorize).toHaveBeenCalledWith('sample owner', 'Rent'));
  });

  it('renders nothing when there is nothing to ask', () => {
    const queryClient = new QueryClient();
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <RecurringQuestion merchant="sample owner" state="NONE" answer={null} amount={10} />
      </QueryClientProvider>,
    );

    expect(container).toBeEmptyDOMElement();
  });
});
