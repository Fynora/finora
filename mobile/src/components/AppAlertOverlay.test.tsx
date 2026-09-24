import { act, fireEvent, render, screen, within } from '@testing-library/react-native';
import { AccessibilityInfo, BackHandler, Modal, ScrollView, Text } from 'react-native';
import { AppAlertOverlay } from './AppAlertOverlay';
import { AppCoveredProvider, AppModal } from './AppModal';
import { AppAlert, ROOT_ALERT_CONTAINER, __resetAppAlertForTests } from '../lib/appAlert';
import { ThemeProvider } from '../theme';

function Root({
  covered = false,
  sheet = false,
  onSheetClose = jest.fn(),
  hidden = false,
}: { covered?: boolean; sheet?: boolean; onSheetClose?: () => void; hidden?: boolean }) {
  return (
    <ThemeProvider>
      <AppCoveredProvider value={covered}>
        <Text>screen</Text>
        <AppModal visible={sheet} onRequestClose={onSheetClose}>
          <Text>sheet body</Text>
        </AppModal>
        <AppAlertOverlay containerId={ROOT_ALERT_CONTAINER} hidden={hidden} />
      </AppCoveredProvider>
    </ThemeProvider>
  );
}

beforeEach(() => __resetAppAlertForTests());

describe('AppAlert at the root', () => {
  it('shows nothing until something raises an alert', () => {
    render(<Root />);
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows the title, message and buttons of an alert', () => {
    render(<Root />);
    act(() => {
      AppAlert.alert('Delete this account?', '"Savings" will be removed.', [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Delete', style: 'destructive' },
      ]);
    });

    expect(screen.getByText('Delete this account?')).toBeTruthy();
    expect(screen.getByText('"Savings" will be removed.')).toBeTruthy();
    expect(screen.getByText('Cancel')).toBeTruthy();
    expect(screen.getByText('Delete')).toBeTruthy();
  });

  it('gives an alert with no buttons a single OK, as Alert.alert does', () => {
    render(<Root />);
    act(() => {
      AppAlert.alert('Email verified');
    });
    fireEvent.press(screen.getByText('OK'));

    expect(screen.queryByText('Email verified')).toBeNull();
  });

  it('closes the alert and runs the pressed button exactly once', () => {
    const onPress = jest.fn();
    render(<Root />);
    act(() => {
      AppAlert.alert('Sure?', undefined, [{ text: 'Yes', onPress }]);
    });

    fireEvent.press(screen.getByText('Yes'));

    expect(onPress).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Sure?')).toBeNull();
  });

  // The foreground-push queue shows the next alert from inside a button's onPress; that only works
  // if the current alert is already gone by then.
  it('closes the alert BEFORE running onPress, so onPress can raise the next one', () => {
    render(<Root />);
    act(() => {
      AppAlert.alert('First', undefined, [{ text: 'OK', onPress: () => AppAlert.alert('Second') }]);
    });

    fireEvent.press(screen.getByText('OK'));

    expect(screen.queryByText('First')).toBeNull();
    expect(screen.getByText('Second')).toBeTruthy();
  });

  it('queues an alert raised while another is showing, and shows it once the first is dismissed', () => {
    render(<Root />);
    act(() => {
      AppAlert.alert('First');
      AppAlert.alert('Second');
    });
    expect(screen.getByText('First')).toBeTruthy();
    expect(screen.queryByText('Second')).toBeNull();

    fireEvent.press(screen.getByText('OK'));

    expect(screen.getByText('Second')).toBeTruthy();
  });

  it('dismisses on a backdrop tap and calls onDismiss, without running any button', () => {
    const onPress = jest.fn();
    const onDismiss = jest.fn();
    render(<Root />);
    act(() => {
      AppAlert.alert('Sure?', undefined, [{ text: 'Yes', onPress }], { cancelable: true, onDismiss });
    });

    fireEvent.press(screen.getByTestId('app-alert-backdrop', { includeHiddenElements: true }));

    expect(screen.queryByText('Sure?')).toBeNull();
    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(onPress).not.toHaveBeenCalled();
  });

  // react-native's Alert is NOT dismissible by an outside tap or Android back unless the caller
  // asks (cancelable defaults to false; iOS never allows it). 27 of the app's 29 alerts never ask,
  // and some finish work in a button's onPress, so the default has to match.
  it('is not dismissed by a backdrop tap unless the caller asked for it, like Alert.alert', () => {
    const onDismiss = jest.fn();
    render(<Root />);
    act(() => {
      AppAlert.alert('Sure?', undefined, [{ text: 'Yes' }], { onDismiss });
    });

    fireEvent.press(screen.getByTestId('app-alert-backdrop', { includeHiddenElements: true }));

    expect(screen.getByText('Sure?')).toBeTruthy();
    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('ignores a backdrop tap when the alert is not cancelable', () => {
    const onDismiss = jest.fn();
    render(<Root />);
    act(() => {
      AppAlert.alert('Read this', undefined, [{ text: 'OK' }], { cancelable: false, onDismiss });
    });

    fireEvent.press(screen.getByTestId('app-alert-backdrop', { includeHiddenElements: true }));

    expect(screen.getByText('Read this')).toBeTruthy();
    expect(onDismiss).not.toHaveBeenCalled();
  });

  // A native alert is announced by the OS as it appears. This one is just views, so without an
  // explicit announcement a screen-reader user would not know one had appeared.
  describe('screen reader announcement', () => {
    it('announces the title and message when an alert appears', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
      render(<Root />);

      act(() => {
        AppAlert.alert('Delete this account?', '"Savings" will be removed.');
      });

      expect(announce).toHaveBeenCalledWith('Delete this account?. "Savings" will be removed.');
    });

    it('announces just the title when there is no message, and only once per alert', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
      const view = render(<Root />);

      act(() => {
        AppAlert.alert('Email verified');
      });
      view.rerender(<Root />);

      expect(announce).toHaveBeenCalledTimes(1);
      expect(announce).toHaveBeenCalledWith('Email verified');
    });

    it('does not announce an alert nobody can reach (the app is locked)', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
      render(<Root hidden />);

      act(() => {
        AppAlert.alert('Delete this account?', '"Savings" will be removed.');
      });

      expect(announce).not.toHaveBeenCalled();
    });

    it('announces it once when it becomes reachable, after unlock', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
      const view = render(<Root hidden />);
      act(() => {
        AppAlert.alert('Delete this account?');
      });

      view.rerender(<Root hidden={false} />);

      expect(announce).toHaveBeenCalledTimes(1);
    });
  });

  // Server error text can be long; a native alert scrolls, so this must too rather than pushing its
  // buttons off a small screen.
  it('puts the message in a scroll view, so a long one cannot push the buttons off screen', () => {
    render(<Root />);
    act(() => {
      AppAlert.alert('Import failed', 'A very long explanation. '.repeat(60), [{ text: 'OK' }]);
    });

    expect(within(screen.UNSAFE_getByType(ScrollView)).getByText(/A very long explanation/)).toBeTruthy();
    expect(screen.getByText('OK')).toBeTruthy();
  });

  // Root only: while the app is locked the alert waits underneath the lock screen, where a screen
  // reader must not be able to land on it and Android back must not dismiss it unseen.
  describe('while hidden (the app is locked)', () => {
    it('is not reachable by touch or screen readers', () => {
      render(<Root hidden />);
      act(() => {
        AppAlert.alert('Delete this account?');
      });

      expect(screen.queryByText('Delete this account?')).toBeNull();
      expect(screen.getByText('Delete this account?', { includeHiddenElements: true })).toBeTruthy();
    });

    it('does not take over the Android back button', () => {
      const addListener = jest.spyOn(BackHandler, 'addEventListener');
      render(<Root hidden />);
      act(() => {
        AppAlert.alert('Delete this account?');
      });

      expect(addListener).not.toHaveBeenCalled();
    });
  });

  describe('Android back button', () => {
    function captureBackHandler() {
      let handler: (() => boolean) | undefined;
      jest.spyOn(BackHandler, 'addEventListener').mockImplementation((_event, listener) => {
        handler = listener as () => boolean;
        return { remove: jest.fn() };
      });
      return () => handler;
    }

    it('dismisses a cancelable alert, calling onDismiss, and consumes the press', () => {
      const backHandler = captureBackHandler();
      const onDismiss = jest.fn();
      render(<Root />);
      act(() => {
        AppAlert.alert('Sure?', undefined, [{ text: 'Yes' }], { cancelable: true, onDismiss });
      });

      let consumed: boolean | undefined;
      act(() => {
        consumed = backHandler()?.();
      });

      expect(consumed).toBe(true);
      expect(screen.queryByText('Sure?')).toBeNull();
      expect(onDismiss).toHaveBeenCalledTimes(1);
    });

    it('consumes the press but keeps an alert up unless the caller asked for it to be cancelable', () => {
      const backHandler = captureBackHandler();
      render(<Root />);
      act(() => {
        AppAlert.alert('Read this', undefined, [{ text: 'OK' }]);
      });

      let consumed: boolean | undefined;
      act(() => {
        consumed = backHandler()?.();
      });

      expect(consumed).toBe(true);
      expect(screen.getByText('Read this')).toBeTruthy();
    });

    it('does not intercept back when there is no alert', () => {
      const backHandler = captureBackHandler();
      render(<Root />);

      expect(backHandler()).toBeUndefined();
    });
  });
});

// A native Modal is above anything in-tree, so an alert raised from inside a sheet has to be drawn
// INSIDE that sheet -- and never as a second sibling native modal, which iOS may not present on top
// of the sheet already up.
describe('AppAlert while a sheet (AppModal) is open', () => {
  it('draws the alert inside the sheet, not at the root', () => {
    render(<Root sheet />);
    act(() => {
      AppAlert.alert('Delete this transaction?');
    });

    expect(screen.getAllByText('Delete this transaction?')).toHaveLength(1);
    expect(within(screen.UNSAFE_getByType(Modal)).getByText('Delete this transaction?')).toBeTruthy();
  });

  it('sends Android back to the alert first, so the sheet underneath is not closed', () => {
    const onSheetClose = jest.fn();
    const onDismiss = jest.fn();
    render(<Root sheet onSheetClose={onSheetClose} />);
    act(() => {
      AppAlert.alert('Delete this transaction?', undefined, [{ text: 'Cancel', style: 'cancel' }], {
        cancelable: true,
        onDismiss,
      });
    });

    act(() => screen.UNSAFE_getByType(Modal).props.onRequestClose());

    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(onSheetClose).not.toHaveBeenCalled();
  });

  it('still keeps Android back away from the sheet when the alert is not cancelable (the default)', () => {
    const onSheetClose = jest.fn();
    render(<Root sheet onSheetClose={onSheetClose} />);
    act(() => {
      AppAlert.alert('Delete this transaction?', undefined, [{ text: 'Cancel', style: 'cancel' }]);
    });

    act(() => screen.UNSAFE_getByType(Modal).props.onRequestClose());

    expect(onSheetClose).not.toHaveBeenCalled();
    expect(within(screen.UNSAFE_getByType(Modal)).getByText('Delete this transaction?')).toBeTruthy();
  });

  // The alert is in the tree, not a native dialog that traps a screen reader's focus, so the sheet
  // behind it has to be hidden from accessibility or TalkBack could reach it.
  it('hides the sheet from screen readers while its alert is up, and shows it again after', () => {
    render(<Root sheet />);
    act(() => {
      AppAlert.alert('Delete this transaction?', undefined, [{ text: 'Cancel' }]);
    });

    expect(screen.queryByText('sheet body')).toBeNull();
    expect(screen.getByText('sheet body', { includeHiddenElements: true })).toBeTruthy();

    fireEvent.press(screen.getByText('Cancel'));

    expect(screen.getByText('sheet body')).toBeTruthy();
  });

  it('lets Android back close the sheet as usual when no alert is up', () => {
    const onSheetClose = jest.fn();
    render(<Root sheet onSheetClose={onSheetClose} />);

    act(() => screen.UNSAFE_getByType(Modal).props.onRequestClose());

    expect(onSheetClose).toHaveBeenCalledTimes(1);
  });

  it('moves the alert back to the root when the sheet closes', () => {
    const view = render(<Root sheet />);
    act(() => {
      AppAlert.alert('Delete this transaction?');
    });
    expect(within(screen.UNSAFE_getByType(Modal)).queryByText('Delete this transaction?')).toBeTruthy();

    view.rerender(<Root sheet={false} />);

    expect(screen.getAllByText('Delete this transaction?')).toHaveLength(1);
  });

  // The whole point: a native alert could not be taken down when the app locked.
  it('takes the sheet AND its alert off screen when the app is covered, and both are back after', () => {
    const view = render(<Root sheet />);
    act(() => {
      AppAlert.alert('Delete this transaction?');
    });

    view.rerender(<Root sheet covered />);
    expect(screen.queryByText('sheet body', { includeHiddenElements: true })).toBeNull();
    // Waiting at the root now, which the lock screen covers like the rest of the app.
    expect(within(screen.UNSAFE_getByType(Modal)).queryByText('Delete this transaction?')).toBeNull();

    view.rerender(<Root sheet covered={false} />);
    expect(within(screen.UNSAFE_getByType(Modal)).getByText('Delete this transaction?')).toBeTruthy();
    // The sheet is back too, but hidden from screen readers while its alert is up (see above).
    expect(screen.getByText('sheet body', { includeHiddenElements: true })).toBeTruthy();
  });
});
