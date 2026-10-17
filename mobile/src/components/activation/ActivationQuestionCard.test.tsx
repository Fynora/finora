import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { activationApi } from '../../api/endpoints';
import { ActivationQuestionCard } from './ActivationQuestionCard';
import { markActivationAnsweredToday, resetActivationAnsweredForTests } from '../../lib/activationQuestions';

// Hoisted above the imports by babel-jest.
jest.mock('../../api/endpoints', () => ({
  activationApi: { question: jest.fn(), answer: jest.fn() },
}));
// Pinned to "unknown", as on a first render: GlassSurface switches between its solid and glass
// renders once the real setting resolves, re-mounting the card's children mid-test.
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => null) }));

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
    resetActivationAnsweredForTests();
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

  it('renders nothing once today is answered, whatever the server says', async () => {
    markActivationAnsweredToday();
    question.mockResolvedValue('NO_FILE');
    renderCard();

    await waitFor(() => expect(question).toHaveBeenCalled());
    await act(async () => {});
    expect(screen.queryByTestId('activation-question-card')).not.toBeOnTheScreen();
  });

  it('closes itself when the server refuses the answer, leaving no empty card', async () => {
    question.mockResolvedValue('NO_FILE');
    answer.mockRejectedValue({ response: { status: 409 } });
    renderCard();

    fireEvent.press(await screen.findByText('Not now'));

    await waitFor(() => expect(screen.queryByTestId('activation-question-card')).not.toBeOnTheScreen());
  });

  it('keeps its Thanks after an accepted answer', async () => {
    question.mockResolvedValue('NO_FILE');
    renderCard();

    fireEvent.press(await screen.findByText('Not now'));

    expect(await screen.findByText('Thanks.')).toBeOnTheScreen();
  });

  it('closeButtonPostsSkipped', async () => {
    question.mockResolvedValue('NOT_CONFIRMED');
    renderCard();

    fireEvent.press(await screen.findByLabelText('Close'));

    expect(answer).toHaveBeenCalledWith('NOT_CONFIRMED', 'SKIPPED');
    expect(screen.queryByTestId('activation-question-card')).not.toBeOnTheScreen();
  });
});
