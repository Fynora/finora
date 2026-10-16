import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AccessibilityInfo, Platform, Pressable, Text } from 'react-native';
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

  describe('an error', () => {
    function TwoTriggers() {
      const { showToast } = useToast();
      return (
        <>
          <Pressable accessibilityRole="button" onPress={() => showToast('Could not save that category.', undefined, 'error')}>
            <Text>fail</Text>
          </Pressable>
          <Pressable accessibilityRole="button" onPress={() => showToast('Saved Food for Swiggy')}>
            <Text>succeed</Text>
          </Pressable>
        </>
      );
    }
    const renderTwo = () => render(<ThemeProvider><ToastProvider><TwoTriggers /></ToastProvider></ThemeProvider>);

    it('shows without the tick a success carries', () => {
      renderTwo();
      fireEvent.press(screen.getByText('fail'));
      expect(screen.getByText('Could not save that category.')).toBeTruthy();
      expect(screen.queryByText(/✓/)).toBeNull();
    });

    // Two saves in flight: the first fails, the second succeeds a moment later. The success must
    // not wipe the failure off the screen before it can be read.
    it('is not replaced by a success while it shows, and its 3 seconds run on', () => {
      jest.useFakeTimers();
      renderTwo();
      fireEvent.press(screen.getByText('fail'));
      act(() => { jest.advanceTimersByTime(1000); });
      fireEvent.press(screen.getByText('succeed'));
      expect(screen.getByText('Could not save that category.')).toBeTruthy();
      expect(screen.queryByText(/Saved Food/)).toBeNull();
      act(() => { jest.advanceTimersByTime(2000); });
      expect(screen.queryByText('Could not save that category.')).toBeNull();
      jest.useRealTimers();
    });

    // A failed save, retried at once, succeeds while the failure still shows. Dropping that success
    // left "Could not save" as the last word on a save that had worked; it shows once the error's
    // own time is up instead, for its full 3 seconds.
    it('holds a success that arrives while it shows, and shows it once its time is up', () => {
      jest.useFakeTimers();
      renderTwo();
      fireEvent.press(screen.getByText('fail'));
      act(() => { jest.advanceTimersByTime(1000); });
      fireEvent.press(screen.getByText('succeed'));
      expect(screen.queryByText(/Saved Food/)).toBeNull();
      act(() => { jest.advanceTimersByTime(2000); });
      expect(screen.queryByText('Could not save that category.')).toBeNull();
      expect(screen.getByText(/Saved Food for Swiggy/)).toBeTruthy();
      act(() => { jest.advanceTimersByTime(2900); });
      expect(screen.getByText(/Saved Food for Swiggy/)).toBeTruthy();
      act(() => { jest.advanceTimersByTime(100); });
      expect(screen.queryByText(/Saved Food/)).toBeNull();
      jest.useRealTimers();
    });

    it('drops a held success when a later error replaces the first', () => {
      jest.useFakeTimers();
      renderTwo();
      fireEvent.press(screen.getByText('fail'));
      fireEvent.press(screen.getByText('succeed'));
      fireEvent.press(screen.getByText('fail'));
      act(() => { jest.advanceTimersByTime(3000); });
      expect(screen.queryByText(/Saved Food/)).toBeNull();
      expect(screen.queryByTestId('toast')).toBeNull();
      jest.useRealTimers();
    });

    it('replaces a success still showing', () => {
      renderTwo();
      fireEvent.press(screen.getByText('succeed'));
      fireEvent.press(screen.getByText('fail'));
      expect(screen.getByText('Could not save that category.')).toBeTruthy();
      expect(screen.queryByText(/Saved Food/)).toBeNull();
    });
  });

  // VoiceOver does not read accessibilityLiveRegion (Android-only), so on iOS a toast was silent.
  describe('on iOS', () => {
    const originalOS = Platform.OS;
    beforeEach(() => { Platform.OS = 'ios'; });
    afterEach(() => { Platform.OS = originalOS; });

    it('announces a toast to VoiceOver, title and body', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
      render(<ThemeProvider><ToastProvider><Trigger /></ToastProvider></ThemeProvider>);
      fireEvent.press(screen.getByText('trigger'));
      expect(announce).toHaveBeenCalledWith('Goal Created. Your Emergency Fund goal is now active.');
      announce.mockRestore();
    });

    it('waits to announce a toast held behind the launch animation until it shows', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
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
      expect(announce).not.toHaveBeenCalled();
      rerender(tree(false));
      expect(announce).toHaveBeenCalledTimes(1);
      announce.mockRestore();
    });
  });
});
