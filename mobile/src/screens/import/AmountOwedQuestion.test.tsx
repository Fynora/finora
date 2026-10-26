import { fireEvent, render, screen } from '@testing-library/react-native';
import { AmountOwedQuestion } from './AmountOwedQuestion';

function renderQuestion(props: Partial<React.ComponentProps<typeof AmountOwedQuestion>> = {}) {
  const handlers = { onChange: jest.fn(), onSkip: jest.fn(), onUndoSkip: jest.fn() };
  render(<AmountOwedQuestion value="" skipped={false} {...handlers} {...props} />);
  return handlers;
}

describe('AmountOwedQuestion', () => {
  it('asks the question with a labelled amount field and a way to skip', () => {
    const { onChange, onSkip } = renderQuestion();
    expect(screen.getByText('How much do you owe on this card today?')).toBeOnTheScreen();
    expect(screen.getByText(/This statement doesn't show your balance/)).toBeOnTheScreen();
    fireEvent.changeText(screen.getByLabelText('Amount owed today (₹)'), '12345.67');
    expect(onChange).toHaveBeenCalledWith('12345.67');
    fireEvent.press(screen.getByRole('button', { name: 'Skip for now' }));
    expect(onSkip).toHaveBeenCalled();
  });

  it('says what an invalid amount needs', () => {
    renderQuestion({ value: '-5' });
    expect(screen.getByText('Enter the amount in rupees, like 12345.67')).toBeOnTheScreen();
  });

  it('once skipped, says what the card will show and offers to answer after all', () => {
    const { onUndoSkip } = renderQuestion({ skipped: true });
    expect(screen.queryByLabelText('Amount owed today (₹)')).not.toBeOnTheScreen();
    expect(screen.getByText(/the card will show "Amount owed not known"/)).toBeOnTheScreen();
    fireEvent.press(screen.getByRole('button', { name: 'Answer now' }));
    expect(onUndoSkip).toHaveBeenCalled();
  });
});
