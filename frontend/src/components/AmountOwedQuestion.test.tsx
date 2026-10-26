import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { AmountOwedQuestion } from './AmountOwedQuestion';

function renderQuestion(props: Partial<React.ComponentProps<typeof AmountOwedQuestion>> = {}) {
  const handlers = { onChange: vi.fn(), onSkip: vi.fn(), onUndoSkip: vi.fn() };
  render(<AmountOwedQuestion idPrefix="t" value="" skipped={false} {...handlers} {...props} />);
  return handlers;
}

describe('AmountOwedQuestion', () => {
  it('asks the question with a labelled amount field and a way to skip', () => {
    const { onChange, onSkip } = renderQuestion();
    expect(screen.getByText('How much do you owe on this card today?')).toBeInTheDocument();
    expect(screen.getByText(/This statement doesn't show your balance/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Amount owed today (₹)'), { target: { value: '12345.67' } });
    expect(onChange).toHaveBeenCalledWith('12345.67');
    fireEvent.click(screen.getByRole('button', { name: 'Skip for now' }));
    expect(onSkip).toHaveBeenCalled();
  });

  it('says what an invalid amount needs', () => {
    renderQuestion({ value: '-5' });
    expect(screen.getByRole('alert')).toHaveTextContent('Enter the amount in rupees, like 12345.67');
  });

  it('once skipped, says what the card will show and offers to answer after all', () => {
    const { onUndoSkip } = renderQuestion({ skipped: true });
    expect(screen.queryByLabelText('Amount owed today (₹)')).not.toBeInTheDocument();
    expect(screen.getByText(/the card will show "Amount owed not known"/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Answer now' }));
    expect(onUndoSkip).toHaveBeenCalled();
  });
});
