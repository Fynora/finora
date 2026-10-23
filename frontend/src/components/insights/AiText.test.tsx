import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { aiInsightsApi } from '../../api/endpoints';
import { AiText } from './AiText';

// Every figure here is invented.
vi.mock('../../api/endpoints', () => ({ aiInsightsApi: { feedback: vi.fn() } }));

const feedback = vi.mocked(aiInsightsApi.feedback);
const TEXT = 'You spent ₹48,000 in September, ₹8,000 more than your usual month.';
const BASIS = 'Compared with your usual month (Jun–Aug)';

function pressed(name: string) {
  return screen.getByRole('button', { name }).getAttribute('aria-pressed');
}

beforeEach(() => {
  feedback.mockReset().mockResolvedValue(undefined);
});

describe('AiText', () => {
  it('shows the label, basis and thumbs only for AI text', () => {
    const { rerender } = render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} />);
    expect(screen.getByText(TEXT)).toBeInTheDocument();
    expect(screen.getByText('Written by AI')).toBeInTheDocument();
    expect(screen.getByText(BASIS)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Helpful' })).toBeInTheDocument();

    // Rules text: no label, and nothing to rate.
    rerender(<AiText id={null} text={TEXT} writtenByAi={false} basis={BASIS} />);
    expect(screen.getByText(TEXT)).toBeInTheDocument();
    expect(screen.queryByText('Written by AI')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Helpful' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Not helpful' })).not.toBeInTheDocument();
  });

  it('thumbsDownReasons: offers three reasons after a thumbs down and sends the chosen one', async () => {
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} />);
    expect(screen.queryByRole('button', { name: 'Wrong number' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Not helpful' }));
    await waitFor(() => expect(feedback).toHaveBeenCalledWith('t-1', 'NOT_HELPFUL', null));
    for (const reason of ['Wrong number', 'Not useful', 'Confusing']) {
      expect(screen.getByRole('button', { name: reason })).toBeInTheDocument();
    }

    await userEvent.click(screen.getByRole('button', { name: 'Wrong number' }));
    await waitFor(() => expect(feedback).toHaveBeenLastCalledWith('t-1', 'NOT_HELPFUL', 'WRONG_NUMBER'));
    expect(feedback).toHaveBeenCalledTimes(2);
    // Announced, so a screen reader hears the reason was taken.
    expect(screen.getByRole('status')).toHaveTextContent('Thanks for telling us.');
    expect(screen.queryByRole('button', { name: 'Wrong number' })).not.toBeInTheDocument();
  });

  it('clears a thumb on a second click of the same one', async () => {
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} />);
    await userEvent.click(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(feedback).toHaveBeenCalledWith('t-1', 'HELPFUL', null));
    expect(pressed('Helpful')).toBe('true');

    await userEvent.click(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(feedback).toHaveBeenLastCalledWith('t-1', null, null));
    expect(pressed('Helpful')).toBe('false');
  });

  it('takes the thumb back when the server does not save it', async () => {
    feedback.mockRejectedValue(new Error('offline'));
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} />);
    await userEvent.click(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(pressed('Helpful')).toBe('false'));
  });

  it('shows the thumb saved earlier', () => {
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} rating="HELPFUL" />);
    expect(pressed('Helpful')).toBe('true');
    expect(pressed('Not helpful')).toBe('false');
  });

  it('shows a saved thumbs-down with its reason as answered', () => {
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} rating="NOT_HELPFUL" ratingReason="CONFUSING" />);
    expect(pressed('Not helpful')).toBe('true');
    expect(screen.getByText('Thanks for telling us.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Wrong number' })).not.toBeInTheDocument();
  });

  it('offers the reasons for a saved thumbs-down that has none', () => {
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} rating="NOT_HELPFUL" ratingReason={null} />);
    expect(screen.getByRole('button', { name: 'Wrong number' })).toBeInTheDocument();
  });

  it('tells its owner what was saved, once the server has it, and not when it was refused', async () => {
    const onFeedback = vi.fn();
    render(<AiText id="t-1" text={TEXT} writtenByAi basis={BASIS} onFeedback={onFeedback} />);
    await userEvent.click(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(onFeedback).toHaveBeenCalledWith('HELPFUL', null));

    feedback.mockRejectedValueOnce(new Error('offline'));
    await userEvent.click(screen.getByRole('button', { name: 'Not helpful' }));
    await waitFor(() => expect(pressed('Helpful')).toBe('true'));
    expect(onFeedback).toHaveBeenCalledTimes(1);
  });
});
