import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { useCategoryScopePrompt, type ScopeAnswer } from './CategoryScopePrompt';
import { transactionsApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  transactionsApi: { similar: vi.fn() },
}));

/** Asks on click and prints the answer, the way a caller awaits it before saving. */
function Harness() {
  const { ask, prompt } = useCategoryScopePrompt();
  const [answer, setAnswer] = useState<string>('none yet');
  return (
    <>
      <button onClick={async () => {
        const a: ScopeAnswer = await ask('t1');
        setAnswer(a === null ? 'closed' : a === undefined ? 'no scope' : a);
      }}>save</button>
      <p data-testid="answer">{answer}</p>
      {prompt}
    </>
  );
}

// No mockReset in a beforeEach: every test sets its own answer, and under this vitest a reset
// followed by a throwing implementation fails the test even though ask() catches the throw
// (measured: the same test passes without the reset).
describe('useCategoryScopePrompt', () => {
  it('asks nothing when no other transaction is from the payee', async () => {
    vi.mocked(transactionsApi.similar).mockResolvedValue({ similar: 0, keptByUser: 2 });
    render(<Harness />);

    await userEvent.click(screen.getByText('save'));

    await waitFor(() => expect(screen.getByTestId('answer')).toHaveTextContent('SIMILAR'));
    expect(screen.queryByTestId('category-scope-dialog')).not.toBeInTheDocument();
  });

  it('offers all of them or only this one, and says which keep their category', async () => {
    vi.mocked(transactionsApi.similar).mockResolvedValue({ similar: 4, keptByUser: 1 });
    render(<Harness />);

    await userEvent.click(screen.getByText('save'));

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(/4 other transactions from the same payee/)).toBeInTheDocument();
    expect(screen.getByText(/1 transaction you categorised yourself will keep its category/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'All 5' }));
    await waitFor(() => expect(screen.getByTestId('answer')).toHaveTextContent('SIMILAR'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('returns ONLY_THIS for "Only this one"', async () => {
    vi.mocked(transactionsApi.similar).mockResolvedValue({ similar: 1, keptByUser: 0 });
    render(<Harness />);

    await userEvent.click(screen.getByText('save'));
    await userEvent.click(await screen.findByRole('button', { name: 'Only this one' }));

    await waitFor(() => expect(screen.getByTestId('answer')).toHaveTextContent('ONLY_THIS'));
  });

  it('saves nothing when the question is closed with Escape', async () => {
    vi.mocked(transactionsApi.similar).mockResolvedValue({ similar: 2, keptByUser: 0 });
    render(<Harness />);

    await userEvent.click(screen.getByText('save'));
    await screen.findByRole('dialog');
    await userEvent.keyboard('{Escape}');

    await waitFor(() => expect(screen.getByTestId('answer')).toHaveTextContent('closed'));
  });

  it('sends no scope when the lookup fails, so the server keeps its old behaviour', async () => {
    // An async throw, not mockRejectedValue: that builds the rejection up front, before ask() is
    // there to catch it, and the runner reports it as unhandled.
    vi.mocked(transactionsApi.similar).mockImplementation(async () => { throw new Error('network'); });
    render(<Harness />);

    await userEvent.click(screen.getByText('save'));

    await waitFor(() => expect(screen.getByTestId('answer')).toHaveTextContent('no scope'));
  });
});
