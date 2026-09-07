import { render, screen, fireEvent } from '@testing-library/react-native';
import { WelcomeScreen } from './WelcomeScreen';

describe('WelcomeScreen', () => {
  it('calls onStart when Start Setup is pressed', () => {
    const onStart = jest.fn();
    render(<WelcomeScreen onStart={onStart} onSkip={jest.fn()} />);
    fireEvent.press(screen.getByText('Start Setup'));
    expect(onStart).toHaveBeenCalled();
  });

  it('calls onSkip when Skip for Now is pressed', () => {
    const onSkip = jest.fn();
    render(<WelcomeScreen onStart={jest.fn()} onSkip={onSkip} />);
    fireEvent.press(screen.getByText('Skip for Now'));
    expect(onSkip).toHaveBeenCalled();
  });

  it('shows the error message when passed one', () => {
    render(<WelcomeScreen onStart={jest.fn()} onSkip={jest.fn()} error="Something went wrong." />);
    expect(screen.getByText('Something went wrong.')).toBeTruthy();
  });

  it('shows no error text when error is not passed', () => {
    render(<WelcomeScreen onStart={jest.fn()} onSkip={jest.fn()} />);
    expect(screen.queryByText(/went wrong/i)).toBeNull();
  });
});
