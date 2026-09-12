import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { Text, Pressable } from 'react-native';
import { ToastProvider, useToast } from './ToastContext';
import { ThemeProvider } from '../theme';

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
});
