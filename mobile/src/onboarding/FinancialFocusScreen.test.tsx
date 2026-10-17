import { render, screen, fireEvent } from '@testing-library/react-native';
import { FinancialFocusScreen } from './FinancialFocusScreen';

describe('FinancialFocusScreen', () => {
  // Nothing personalises from these answers yet, so the screen no longer promises it does.
  it('says what the answers are for, without promising personalisation', () => {
    render(<FinancialFocusScreen onContinue={jest.fn()} />);
    expect(screen.getByText('Select all that apply. It helps us understand what you want from Fynora.')).toBeOnTheScreen();
    expect(screen.queryByText(/personali[sz]e/i)).not.toBeOnTheScreen();
  });

  it('calls onContinue with the selected keys', () => {
    const onContinue = jest.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.press(screen.getByText(/Track my spending/));
    fireEvent.press(screen.getByText(/Reduce debt/));
    fireEvent.press(screen.getByText('Continue'));
    expect(onContinue).toHaveBeenCalledWith(['TRACK_SPENDING', 'REDUCE_DEBT']);
  });

  it('allows continuing with nothing selected', () => {
    const onContinue = jest.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.press(screen.getByText('Continue'));
    expect(onContinue).toHaveBeenCalledWith([]);
  });

  it('selecting "Just exploring" clears every other selection', () => {
    const onContinue = jest.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.press(screen.getByText(/Track my spending/));
    fireEvent.press(screen.getByText(/Just exploring/));
    fireEvent.press(screen.getByText('Continue'));
    expect(onContinue).toHaveBeenCalledWith(['EXPLORING']);
  });

  it('shows the error message when passed one', () => {
    render(<FinancialFocusScreen onContinue={jest.fn()} error="Something went wrong." />);
    expect(screen.getByText('Something went wrong.')).toBeTruthy();
  });

  it('shows no error text when error is not passed', () => {
    render(<FinancialFocusScreen onContinue={jest.fn()} />);
    expect(screen.queryByText(/went wrong/i)).toBeNull();
  });
});
