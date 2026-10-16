import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../api/endpoints', () => ({
  activationApi: { question: vi.fn(), answer: vi.fn() },
}));

import { activationApi } from '../../api/endpoints';
import { ActivationQuestionCard } from './ActivationQuestionCard';

const api = vi.mocked(activationApi);

function renderCard() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ActivationQuestionCard />
    </QueryClientProvider>
  );
}

describe('ActivationQuestionCard', () => {
  beforeEach(() => {
    api.question.mockReset();
    api.answer.mockReset().mockResolvedValue(undefined);
  });

  it('rendersNothingWhenNoQuestion', async () => {
    api.question.mockResolvedValue(null);
    const { container } = renderCard();

    await waitFor(() => expect(api.question).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('rendersNothingWhenTheQuestionCannotBeFetched', async () => {
    api.question.mockRejectedValue(new Error('network down'));
    const { container } = renderCard();

    await waitFor(() => expect(api.question).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the question the server chose', async () => {
    api.question.mockResolvedValue('NO_FILE');
    renderCard();

    expect(await screen.findByText('Last time you looked at adding a statement. What stopped you?')).toBeInTheDocument();
  });

  // After a first import, NO_FILE no longer applies; coming back to the dashboard must not flash the
  // old question from the cache while the new answer loads.
  it('never shows a question cached from an earlier visit', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    api.question.mockResolvedValue('NO_FILE');
    const first = render(<QueryClientProvider client={client}><ActivationQuestionCard /></QueryClientProvider>);
    expect(await screen.findByText('Last time you looked at adding a statement. What stopped you?')).toBeInTheDocument();
    first.unmount();

    api.question.mockReturnValue(new Promise(() => {}));
    render(<QueryClientProvider client={client}><ActivationQuestionCard /></QueryClientProvider>);

    expect(screen.queryByText('Last time you looked at adding a statement. What stopped you?')).toBeNull();
  });

  it('closeButtonPostsSkipped', async () => {
    api.question.mockResolvedValue('NOT_CONFIRMED');
    const { container } = renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Close' }));

    expect(api.answer).toHaveBeenCalledWith('NOT_CONFIRMED', 'SKIPPED');
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });
});
