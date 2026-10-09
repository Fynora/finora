import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AccessibilityInfo, Text } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { AppBannerOverlay, SWIPE_DISMISS_DISTANCE } from './AppBannerOverlay';
import { AppCoveredProvider, AppModal } from './AppModal';
import { ROOT_ALERT_CONTAINER, __resetAppAlertForTests } from '../lib/appAlert';
import { AppBanner, getCurrentAppBanner, __resetAppBannerForTests } from '../lib/appBanner';
import { ThemeProvider } from '../theme';

function Root({ hidden = false, sheet = false, covered = false }: { hidden?: boolean; sheet?: boolean; covered?: boolean }) {
  return (
    <SafeAreaProvider
      initialMetrics={{ frame: { x: 0, y: 0, width: 390, height: 844 }, insets: { top: 47, left: 0, right: 0, bottom: 34 } }}
    >
      <ThemeProvider>
        <AppCoveredProvider value={covered}>
          <Text>screen</Text>
          <AppModal visible={sheet}>
            <Text>sheet body</Text>
          </AppModal>
          <AppBannerOverlay containerId={ROOT_ALERT_CONTAINER} hidden={hidden} />
        </AppCoveredProvider>
      </ThemeProvider>
    </SafeAreaProvider>
  );
}

beforeEach(() => {
  jest.useFakeTimers();
  __resetAppAlertForTests();
  __resetAppBannerForTests();
});

afterEach(() => {
  __resetAppBannerForTests();
  __resetAppAlertForTests();
  jest.useRealTimers();
});

describe('AppBannerOverlay', () => {
  it('shows nothing until something raises a banner', () => {
    render(<Root />);
    expect(screen.queryByTestId('app-banner')).toBeNull();
  });

  it('shows the title and message of a banner without taking over the screen', () => {
    render(<Root />);
    act(() => {
      AppBanner.show('Welcome to Fynora', 'Welcome to Fynora body.');
    });

    expect(screen.getByText('Welcome to Fynora')).toBeTruthy();
    expect(screen.getByText('Welcome to Fynora body.')).toBeTruthy();
    // The screen underneath is still there: nothing was replaced or covered by a backdrop.
    expect(screen.getByText('screen')).toBeTruthy();
    expect(screen.queryByTestId('app-alert-backdrop')).toBeNull();
  });

  it('goes away by itself', () => {
    render(<Root />);
    act(() => {
      AppBanner.show('Hello', 'World');
    });
    expect(screen.getByText('Hello')).toBeTruthy();

    act(() => {
      jest.advanceTimersByTime(10_000);
    });

    expect(screen.queryByText('Hello')).toBeNull();
  });

  it('goes away when tapped', () => {
    render(<Root />);
    act(() => {
      AppBanner.show('Hello', 'World');
    });

    fireEvent.press(screen.getByTestId('app-banner'));

    expect(screen.queryByText('Hello')).toBeNull();
    expect(getCurrentAppBanner()).toBeUndefined();
  });

  // No OK button: a finger sliding the card up dismisses it.
  describe('swiping', () => {
    function swipe(fromY: number, toY: number) {
      const card = screen.getByTestId('app-banner-swipe');
      fireEvent(card, 'touchStart', { nativeEvent: { pageY: fromY } });
      fireEvent(card, 'touchMove', { nativeEvent: { pageY: toY } });
      fireEvent(card, 'touchEnd', { nativeEvent: { pageY: toY } });
    }

    beforeEach(() => {
      render(<Root />);
      act(() => {
        AppBanner.show('Hello', 'World');
      });
    });

    it('has no button to press', () => {
      expect(screen.queryByText('OK')).toBeNull();
      expect(screen.queryByRole('button', { name: 'OK' })).toBeNull();
    });

    it('is dismissed by sliding it up', () => {
      swipe(120, 70);

      expect(screen.queryByText('Hello')).toBeNull();
      expect(getCurrentAppBanner()).toBeUndefined();
    });

    it('is dismissed at exactly the threshold and not one point short of it', () => {
      swipe(100, 100 - SWIPE_DISMISS_DISTANCE + 1);
      expect(screen.getByText('Hello')).toBeTruthy();

      swipe(100, 100 - SWIPE_DISMISS_DISTANCE);
      expect(screen.queryByText('Hello')).toBeNull();
    });

    it('stays when slid down, or only nudged', () => {
      swipe(100, 200);
      expect(screen.getByText('Hello')).toBeTruthy();

      swipe(100, 95);
      expect(screen.getByText('Hello')).toBeTruthy();
    });

    it('stays when the finger goes up and comes back before letting go', () => {
      const card = screen.getByTestId('app-banner-swipe');
      fireEvent(card, 'touchStart', { nativeEvent: { pageY: 200 } });
      fireEvent(card, 'touchMove', { nativeEvent: { pageY: 120 } });
      fireEvent(card, 'touchMove', { nativeEvent: { pageY: 195 } });
      fireEvent(card, 'touchEnd', { nativeEvent: { pageY: 195 } });

      expect(screen.getByText('Hello')).toBeTruthy();
    });

    it('can be dismissed by a screen-reader action as well', () => {
      fireEvent(screen.getByTestId('app-banner'), 'accessibilityAction', { nativeEvent: { actionName: 'dismiss' } });

      expect(screen.queryByText('Hello')).toBeNull();
    });
  });

  it('draws nothing while hidden (the app is locked), and appears if it is still current afterwards', () => {
    const view = render(<Root hidden />);
    act(() => {
      AppBanner.show('Hello', 'World');
    });
    expect(screen.queryByText('Hello')).toBeNull();

    view.rerender(<Root hidden={false} />);
    expect(screen.getByText('Hello')).toBeTruthy();
  });

  it('announces a banner to a screen reader once, not on every render', () => {
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
    const view = render(<Root />);
    act(() => {
      AppBanner.show('Hello', 'World');
    });
    view.rerender(<Root />);

    expect(announce).toHaveBeenCalledTimes(1);
    expect(announce).toHaveBeenCalledWith('Hello. World');
    announce.mockRestore();
  });

  // A native Modal sits above everything in the tree, so a banner drawn only at the root would be
  // invisible behind an open sheet and the message lost.
  describe('with a sheet open', () => {
    it('is drawn inside the sheet, exactly once', () => {
      render(<Root sheet />);
      expect(screen.getByText('sheet body')).toBeTruthy();

      act(() => {
        AppBanner.show('Welcome to Fynora', 'Welcome to Fynora body.');
      });

      expect(screen.getAllByText('Welcome to Fynora')).toHaveLength(1);
    });

    it('moves into the sheet when it opens, and back to the root when it closes', () => {
      const view = render(<Root />);
      act(() => {
        AppBanner.show('Hello', 'World');
      });
      expect(screen.getAllByText('Hello')).toHaveLength(1);

      view.rerender(<Root sheet />);
      expect(screen.getAllByText('Hello')).toHaveLength(1);

      view.rerender(<Root />);
      expect(screen.getAllByText('Hello')).toHaveLength(1);
    });

    it('is not drawn by a sheet the lock has hidden', () => {
      render(<Root sheet covered hidden />);
      act(() => {
        AppBanner.show('Hello', 'World');
      });

      expect(screen.queryByText('Hello')).toBeNull();
    });
  });

  // AppModal mounts this overlay, so it has to render wherever a sheet does -- including where no
  // SafeAreaProvider exists, which useSafeAreaInsets() would crash on.
  it('renders without a SafeAreaProvider instead of crashing', () => {
    render(
      <ThemeProvider>
        <AppBannerOverlay containerId={ROOT_ALERT_CONTAINER} />
      </ThemeProvider>
    );
    act(() => {
      AppBanner.show('Hello', 'World');
    });

    expect(screen.getByText('Hello')).toBeTruthy();
  });
});
