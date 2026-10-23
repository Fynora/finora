import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { aiInsightsApi } from '../../api/endpoints';
import { expectHaptics } from '../../test/haptics';
import { AiText } from './AiText';

// Hoisted above the imports by babel-jest. Every figure here is invented.
jest.mock('../../api/endpoints', () => ({ aiInsightsApi: { feedback: jest.fn() } }));

const feedback = aiInsightsApi.feedback as jest.Mock;
const TEXT = 'You spent ₹48,000 in September, ₹8,000 more than your usual month.';
const BASIS = 'Compared with your usual month (Jun–Aug)';

beforeEach(() => {
  feedback.mockReset().mockResolvedValue(undefined);
});

describe('AiText', () => {
  it('shows the label only for AI text', () => {
    const { rerender } = render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} />);
    expect(screen.getByText(TEXT)).toBeTruthy();
    expect(screen.getByText('Written by AI')).toBeTruthy();
    expect(screen.getByText(BASIS)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Helpful' })).toBeTruthy();

    // Rules text: no label, and nothing to rate.
    rerender(<AiText id={null} text={TEXT} writtenByAi={false} basis={BASIS} />);
    expect(screen.getByText(TEXT)).toBeTruthy();
    expect(screen.queryByText('Written by AI')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Helpful' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Not helpful' })).toBeNull();
  });

  it('offers three reasons after a thumbs down and sends the chosen one', async () => {
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} />);
    expect(screen.queryByRole('button', { name: 'Wrong number' })).toBeNull();

    fireEvent.press(screen.getByRole('button', { name: 'Not helpful' }));
    await waitFor(() => expect(feedback).toHaveBeenCalledWith('t-1', 'NOT_HELPFUL', null));
    for (const reason of ['Wrong number', 'Not useful', 'Confusing']) {
      expect(screen.getByRole('button', { name: reason })).toBeTruthy();
    }

    fireEvent.press(screen.getByRole('button', { name: 'Wrong number' }));
    await waitFor(() => expect(feedback).toHaveBeenLastCalledWith('t-1', 'NOT_HELPFUL', 'WRONG_NUMBER'));
    expect(feedback).toHaveBeenCalledTimes(2);
    expect(screen.getByText('Thanks for telling us.')).toBeTruthy();
    expectHaptics({ selection: 2 });
  });

  it('clears a thumb on a second tap of the same one', async () => {
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} />);
    fireEvent.press(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(feedback).toHaveBeenCalledWith('t-1', 'HELPFUL', null));
    expect(screen.getByRole('button', { name: 'Helpful' }).props.accessibilityState).toEqual(
      expect.objectContaining({ selected: true }),
    );

    fireEvent.press(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(feedback).toHaveBeenLastCalledWith('t-1', null, null));
    expect(screen.getByRole('button', { name: 'Helpful' }).props.accessibilityState).toEqual(
      expect.objectContaining({ selected: false }),
    );
  });

  it('takes the thumb back when the server does not save it', async () => {
    feedback.mockRejectedValue(new Error('offline'));
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} />);
    fireEvent.press(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Helpful' }).props.accessibilityState).toEqual(
      expect.objectContaining({ selected: false }),
    ));
  });

  it('gives every control an accessibility label', () => {
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} />);
    // The label and basis are read with the text, so a screen reader hears who wrote it and against what.
    expect(screen.getByLabelText(`Written by AI. ${TEXT} ${BASIS}`)).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Not helpful' }));
    for (const name of ['Helpful', 'Not helpful', 'Wrong number', 'Not useful', 'Confusing']) {
      expect(screen.getByRole('button', { name }).props.accessibilityLabel).toBe(name);
    }
  });

  it('says how many free explanations are left once one is used', () => {
    const { rerender } = render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} freeLeft={4} />);
    expect(screen.getByText('4 of 5 left this month')).toBeTruthy();
    // None used yet, or not on Free: no count.
    rerender(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} freeLeft={5} />);
    expect(screen.queryByText(/left this month/)).toBeNull();
    rerender(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} freeLeft={null} />);
    expect(screen.queryByText(/left this month/)).toBeNull();
  });
});
