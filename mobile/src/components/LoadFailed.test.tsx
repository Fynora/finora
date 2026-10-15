import { fireEvent, render, screen } from '@testing-library/react-native';
import { ThemeProvider } from '../theme';
import { LoadFailed } from './LoadFailed';

describe('LoadFailed', () => {
  it('shows the message and a Try again button that calls onRetry once per press', () => {
    const onRetry = jest.fn();
    render(<ThemeProvider><LoadFailed message="Could not load accounts." onRetry={onRetry} /></ThemeProvider>);

    expect(screen.getByText('Could not load accounts.')).toBeTruthy();
    fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('gives Try again at least the 44pt touch target', () => {
    render(<ThemeProvider><LoadFailed message="x" onRetry={jest.fn()} /></ThemeProvider>);
    const button = screen.getByRole('button', { name: 'Try again' });
    const style = Object.assign({}, ...[button.props.style].flat(Infinity).filter(Boolean));
    expect(style.minHeight).toBeGreaterThanOrEqual(44);
  });
});
