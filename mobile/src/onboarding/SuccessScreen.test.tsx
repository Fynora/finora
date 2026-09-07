import { render, screen, fireEvent } from '@testing-library/react-native';
import { SuccessScreen } from './SuccessScreen';

describe('SuccessScreen', () => {
  it('shows all 6 checklist items', () => {
    render(<SuccessScreen onDone={jest.fn()} />);
    expect(screen.getByText(/Complete your profile/)).toBeTruthy();
    expect(screen.getByText(/Import first statement/)).toBeTruthy();
    expect(screen.getByText(/Review transactions/)).toBeTruthy();
    expect(screen.getByText(/Create a budget/)).toBeTruthy();
    expect(screen.getByText(/Create a goal/)).toBeTruthy();
    expect(screen.getByText(/View insights/)).toBeTruthy();
  });

  it('calls onDone when Import Statement is pressed', () => {
    const onDone = jest.fn();
    render(<SuccessScreen onDone={onDone} />);
    fireEvent.press(screen.getByText('Import Statement'));
    expect(onDone).toHaveBeenCalled();
  });

  it('calls onDone when Connect Account is pressed', () => {
    const onDone = jest.fn();
    render(<SuccessScreen onDone={onDone} />);
    fireEvent.press(screen.getByText('Connect Account'));
    expect(onDone).toHaveBeenCalled();
  });

  it('calls onDone when Go to Dashboard is pressed', () => {
    const onDone = jest.fn();
    render(<SuccessScreen onDone={onDone} />);
    fireEvent.press(screen.getByText('Go to Dashboard'));
    expect(onDone).toHaveBeenCalled();
  });

  it('shows the error message when passed one', () => {
    render(<SuccessScreen onDone={jest.fn()} error="Something went wrong." />);
    expect(screen.getByText('Something went wrong.')).toBeTruthy();
  });

  it('shows no error text when error is not passed', () => {
    render(<SuccessScreen onDone={jest.fn()} />);
    expect(screen.queryByText(/went wrong/i)).toBeNull();
  });
});
