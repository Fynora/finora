import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../api/endpoints', () => ({
  activationApi: { question: vi.fn(), answer: vi.fn() },
}));

import { activationApi } from '../../api/endpoints';
import { ActivationQuestionCard } from './ActivationQuestionCard';
import { markActivationAnsweredToday, resetActivationAnsweredForTests } from '../../lib/activationQuestions';

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
    resetActivationAnsweredForTests();
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

  // Never an empty card: the question inside would not show once today is answered.
  it('renders nothing once today is answered, whatever the server says', async () => {
    markActivationAnsweredToday();
    api.question.mockResolvedValue('NO_FILE');
    const { container } = renderCard();

    await waitFor(() => expect(api.question).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(container).toBeEmptyDOMElement();
  });

  it('closes itself when the server refuses the answer, leaving no empty card', async () => {
    api.question.mockResolvedValue('NO_FILE');
    api.answer.mockRejectedValue({ response: { status: 409 } });
    const { container } = renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });

  it('keeps its Thanks after an accepted answer', async () => {
    api.question.mockResolvedValue('NO_FILE');
    renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));

    expect(await screen.findByText('Thanks.')).toBeInTheDocument();
  });

  it('closeButtonPostsSkipped', async () => {
    api.question.mockResolvedValue('NOT_CONFIRMED');
    const { container } = renderCard();

    await userEvent.click(await screen.findByRole('button', { name: 'Close' }));

    expect(api.answer).toHaveBeenCalledWith('NOT_CONFIRMED', 'SKIPPED');
    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });
});
