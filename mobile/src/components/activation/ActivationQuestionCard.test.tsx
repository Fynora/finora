import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { activationApi } from '../../api/endpoints';
import { ActivationQuestionCard } from './ActivationQuestionCard';

// Hoisted above the imports by babel-jest.
jest.mock('../../api/endpoints', () => ({
  activationApi: { question: jest.fn(), answer: jest.fn() },
}));

const question = activationApi.question as jest.Mock;
const answer = activationApi.answer as jest.Mock;

function renderCard() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })}>
      <ActivationQuestionCard />
    </QueryClientProvider>
  );
}

describe('ActivationQuestionCard', () => {
  beforeEach(() => {
    question.mockReset();
    answer.mockReset().mockResolvedValue(undefined);
  });

  it('rendersNothingWhenNoQuestion', async () => {
    question.mockResolvedValue(null);
    renderCard();

    await waitFor(() => expect(question).toHaveBeenCalled());
    expect(screen.queryByTestId('activation-question-card')).not.toBeOnTheScreen();
  });

  it('rendersNothingWhenTheQuestionCannotBeFetched', async () => {
    question.mockRejectedValue(new Error('network down'));
    renderCard();

    await waitFor(() => expect(question).toHaveBeenCalled());
    expect(screen.queryByTestId('activation-question-card')).not.toBeOnTheScreen();
  });

  it('shows the question the server chose', async () => {
    question.mockResolvedValue('NO_FILE');
    renderCard();

    expect(await screen.findByText('Last time you looked at adding a statement. What stopped you?')).toBeOnTheScreen();
  });

  it('never shows a question cached from an earlier visit', async () => {
    // gcTime Infinity: keeps the cached answer (what this test is about) without leaving a timer behind.
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
    question.mockResolvedValue('NO_FILE');
    const first = render(<QueryClientProvider client={client}><ActivationQuestionCard /></QueryClientProvider>);
    expect(await screen.findByText('Last time you looked at adding a statement. What stopped you?')).toBeOnTheScreen();
    first.unmount();

    question.mockReturnValue(new Promise(() => {}));
    render(<QueryClientProvider client={client}><ActivationQuestionCard /></QueryClientProvider>);

    expect(screen.queryByText('Last time you looked at adding a statement. What stopped you?')).not.toBeOnTheScreen();
  });

  it('closeButtonPostsSkipped', async () => {
    question.mockResolvedValue('NOT_CONFIRMED');
    renderCard();

    fireEvent.press(await screen.findByLabelText('Close'));

    expect(answer).toHaveBeenCalledWith('NOT_CONFIRMED', 'SKIPPED');
    expect(screen.queryByTestId('activation-question-card')).not.toBeOnTheScreen();
  });
});
