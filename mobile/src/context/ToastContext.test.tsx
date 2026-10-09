import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { Text, Pressable } from 'react-native';
import { ToastProvider, useToast } from './ToastContext';
import { ThemeProvider } from '../theme';
import { LaunchCoveredProvider } from '../components/AppModal';

function Trigger() {
  const { showToast } = useToast();
  return <Pressable accessibilityRole="button" onPress={() => showToast('Goal Created', 'Your Emergency Fund goal is now active.')}><Text>trigger</Text></Pressable>;
}

function renderTrigger() {
  return render(
    <ThemeProvider>
      <ToastProvider>
        <Trigger />
      </ToastProvider>
    </ThemeProvider>
  );
}

describe('ToastProvider/useToast', () => {
  it('shows nothing until showToast is called', () => {
    renderTrigger();
    expect(screen.queryByText('Goal Created')).toBeNull();
  });

  it('shows the title and body after showToast, then auto-dismisses after 3 seconds', async () => {
    jest.useFakeTimers();
    renderTrigger();

    fireEvent.press(screen.getByText('trigger'));
    expect(screen.getByText(/Goal Created/)).toBeTruthy();
    expect(screen.getByText('Your Emergency Fund goal is now active.')).toBeTruthy();

    act(() => { jest.advanceTimersByTime(3000); });
    expect(screen.queryByText(/Goal Created/)).toBeNull();
    jest.useRealTimers();
  });

  it('a second toast replaces the first and restarts the 3 seconds', () => {
    jest.useFakeTimers();
    renderTrigger();

    fireEvent.press(screen.getByText('trigger'));
    act(() => { jest.advanceTimersByTime(2000); });
    fireEvent.press(screen.getByText('trigger'));
    act(() => { jest.advanceTimersByTime(2000); });
    expect(screen.getByText(/Goal Created/)).toBeTruthy();

    act(() => { jest.advanceTimersByTime(1000); });
    expect(screen.queryByText(/Goal Created/)).toBeNull();
    jest.useRealTimers();
  });

  it('holds a toast raised under the launch animation, and gives it its full 3 seconds afterwards', () => {
    jest.useFakeTimers();
    const tree = (launching: boolean) => (
      <ThemeProvider>
        <LaunchCoveredProvider value={launching}>
          <ToastProvider>
            <Trigger />
          </ToastProvider>
        </LaunchCoveredProvider>
      </ThemeProvider>
    );
    const { rerender } = render(tree(true));

    fireEvent.press(screen.getByText('trigger'));
    act(() => { jest.advanceTimersByTime(5000); });
    expect(screen.queryByText(/Goal Created/)).toBeNull();

    rerender(tree(false));
    expect(screen.getByText(/Goal Created/)).toBeTruthy();
    act(() => { jest.advanceTimersByTime(2900); });
    expect(screen.getByText(/Goal Created/)).toBeTruthy();
    act(() => { jest.advanceTimersByTime(100); });
    expect(screen.queryByText(/Goal Created/)).toBeNull();
    jest.useRealTimers();
  });
});
