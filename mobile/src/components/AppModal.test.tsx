import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { useState } from 'react';
import { Modal, Platform, Text, TextInput } from 'react-native';
import {
  AppCoveredProvider,
  AppModal,
  LaunchCoveredProvider,
  MODAL_DISMISS_FALLBACK_MS,
  NESTED_MODAL_DISMISS_MS,
} from './AppModal';

/** The native Modal AppModal rendered for this testID. */
function nativeModal(testID: string) {
  return screen.UNSAFE_getAllByType(Modal).find((m) => m.props.testID === testID)!;
}

function isOpen(testID: string): boolean {
  return nativeModal(testID).props.visible === true;
}

/** What iOS does when a modal's dismissal has finished. */
function finishDismiss(testID: string) {
  act(() => nativeModal(testID).props.onDismiss?.());
}

/** What iOS does when a modal's presentation has finished. */
function finishShow(testID: string) {
  act(() => nativeModal(testID).props.onShow?.());
}

describe('AppModal', () => {
  it('renders like a plain Modal when the app is not covered', () => {
    render(
      <AppModal visible>
        <Text>body</Text>
      </AppModal>
    );
    expect(screen.getByText('body')).toBeTruthy();
  });

  it('treats an omitted visible prop as visible, as Modal does', () => {
    render(
      <AppModal>
        <Text>body</Text>
      </AppModal>
    );
    expect(screen.getByText('body')).toBeTruthy();
  });

  it('stays hidden when the caller says it is hidden', () => {
    render(
      <AppModal visible={false}>
        <Text>body</Text>
      </AppModal>
    );
    expect(screen.queryByText('body', { includeHiddenElements: true })).toBeNull();
  });

  it('hides itself while the app is covered, even though the caller asked for it visible', () => {
    render(
      <AppCoveredProvider value>
        <AppModal visible>
          <Text>body</Text>
        </AppModal>
      </AppCoveredProvider>
    );
    expect(screen.queryByText('body', { includeHiddenElements: true })).toBeNull();
  });

  it('hides itself while the launch animation covers the app, and shows once it has gone', () => {
    const tree = (launching: boolean) => (
      <LaunchCoveredProvider value={launching}>
        <AppModal visible>
          <Text>body</Text>
        </AppModal>
      </LaunchCoveredProvider>
    );
    const { rerender } = render(tree(true));
    expect(screen.queryByText('body', { includeHiddenElements: true })).toBeNull();

    rerender(tree(false));
    expect(screen.getByText('body')).toBeTruthy();
  });

  it('keeps its own animation when it first opens after the launch animation', () => {
    const tree = (launching: boolean) => (
      <LaunchCoveredProvider value={launching}>
        <AppModal testID="tour" visible animationType="fade">
          <Text>body</Text>
        </AppModal>
      </LaunchCoveredProvider>
    );
    const { rerender } = render(tree(true));
    rerender(tree(false));
    expect(nativeModal('tour').props.animationType).toBe('fade');
  });
});

// iOS: asking a view controller that has another one presented on top of it to dismiss only removes
// the top one. Closing a sheet and the picker inside it in one commit therefore left the sheet's
// empty controller on screen, swallowing every touch, PIN pad included.
describe('AppModal nested inside another AppModal', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => {
    jest.useRealTimers();
    jest.restoreAllMocks();
  });

  const nested = (covered: boolean) => (
    <AppCoveredProvider value={covered}>
      <AppModal testID="sheet" visible animationType="slide">
        <Text>sheet</Text>
        <AppModal testID="picker" visible animationType="slide">
          <Text>picker</Text>
        </AppModal>
      </AppModal>
    </AppCoveredProvider>
  );

  it('closes the inner modal first when the app locks, and the outer one only after it has gone', () => {
    const { rerender } = render(nested(false));
    rerender(nested(true));

    expect(isOpen('picker')).toBe(false);
    expect(isOpen('sheet')).toBe(true);

    finishDismiss('picker');
    expect(isOpen('sheet')).toBe(false);
  });

  it('closes three levels one at a time, innermost first', () => {
    const tree = (covered: boolean) => (
      <AppCoveredProvider value={covered}>
        <AppModal testID="one" visible>
          <AppModal testID="two" visible>
            <AppModal testID="three" visible>
              <Text>deep</Text>
            </AppModal>
          </AppModal>
        </AppModal>
      </AppCoveredProvider>
    );
    const { rerender } = render(tree(false));
    rerender(tree(true));

    expect([isOpen('one'), isOpen('two'), isOpen('three')]).toEqual([true, true, false]);
    finishDismiss('three');
    expect([isOpen('one'), isOpen('two')]).toEqual([true, false]);
    finishDismiss('two');
    expect(isOpen('one')).toBe(false);
  });

  it('closes without an animation while the app is covered, so the sheet does not slide over the PIN pad', () => {
    const { rerender } = render(nested(false));
    rerender(nested(true));

    expect(nativeModal('picker').props.animationType).toBe('none');
    expect(nativeModal('sheet').props.animationType).toBe('none');
  });

  it('does not wait forever when iOS never reports the inner modal as dismissed', () => {
    const { rerender } = render(nested(false));
    rerender(nested(true));
    expect(isOpen('sheet')).toBe(true);

    act(() => jest.advanceTimersByTime(MODAL_DISMISS_FALLBACK_MS));
    expect(isOpen('sheet')).toBe(false);
  });

  it('brings the outer modal back first after unlock, then the inner one once the outer is up', () => {
    const { rerender } = render(nested(false));
    rerender(nested(true));
    finishDismiss('picker');
    finishDismiss('sheet');

    rerender(nested(false));
    expect(isOpen('sheet')).toBe(true);
    expect(isOpen('picker')).toBe(false);

    finishShow('sheet');
    expect(isOpen('picker')).toBe(true);
  });

  it('restores the sheets without an animation, then gives them their own animation back', () => {
    const { rerender } = render(nested(false));
    rerender(nested(true));
    finishDismiss('picker');
    finishDismiss('sheet');

    rerender(nested(false));
    expect(nativeModal('sheet').props.animationType).toBe('none');

    finishShow('sheet');
    expect(nativeModal('sheet').props.animationType).toBe('slide');
  });

  it('still brings the inner modal back when iOS never reports the outer one as shown', () => {
    const { rerender } = render(nested(false));
    rerender(nested(true));
    finishDismiss('picker');
    finishDismiss('sheet');

    rerender(nested(false));
    act(() => jest.advanceTimersByTime(MODAL_DISMISS_FALLBACK_MS));
    expect(isOpen('picker')).toBe(true);
  });

  it('recovers when the app is uncovered before the outer modal had started closing', () => {
    const { rerender } = render(nested(false));
    rerender(nested(true));
    rerender(nested(false));
    act(() => jest.advanceTimersByTime(MODAL_DISMISS_FALLBACK_MS));

    expect(isOpen('sheet')).toBe(true);
    expect(isOpen('picker')).toBe(true);
  });

  it('closes the inner modal first when the caller closes only the outer one', () => {
    const tree = (open: boolean) => (
      <AppModal testID="sheet" visible={open}>
        <AppModal testID="picker" visible>
          <Text>picker</Text>
        </AppModal>
      </AppModal>
    );
    const { rerender } = render(tree(true));
    rerender(tree(false));

    expect(isOpen('picker')).toBe(false);
    expect(isOpen('sheet')).toBe(true);

    finishDismiss('picker');
    expect(isOpen('sheet')).toBe(false);
  });

  it('holds the outer modal through the slide-out when the inner one is unmounted in the same commit', () => {
    const tree = (open: boolean) => (
      <AppModal testID="sheet" visible={open}>
        {open ? (
          <AppModal testID="picker" visible>
            <Text>picker</Text>
          </AppModal>
        ) : null}
      </AppModal>
    );
    const { rerender } = render(tree(true));
    rerender(tree(false));
    expect(isOpen('sheet')).toBe(true);

    act(() => jest.advanceTimersByTime(NESTED_MODAL_DISMISS_MS));
    expect(isOpen('sheet')).toBe(false);
  });

  it('closes a modal with nothing open inside it straight away', () => {
    const tree = (covered: boolean) => (
      <AppCoveredProvider value={covered}>
        <AppModal testID="sheet" visible>
          <AppModal testID="picker" visible={false}>
            <Text>picker</Text>
          </AppModal>
        </AppModal>
      </AppCoveredProvider>
    );
    const { rerender } = render(tree(false));
    rerender(tree(true));
    expect(isOpen('sheet')).toBe(false);
  });

  it('does not make side-by-side modals wait for each other', () => {
    const tree = (covered: boolean) => (
      <AppCoveredProvider value={covered}>
        <AppModal testID="left" visible><Text>left</Text></AppModal>
        <AppModal testID="right" visible><Text>right</Text></AppModal>
      </AppCoveredProvider>
    );
    const { rerender } = render(tree(false));
    rerender(tree(true));
    expect(isOpen('left')).toBe(false);
    expect(isOpen('right')).toBe(false);
  });

  it('does not wait for a dismiss event on Android, which never sends one', () => {
    const original = Platform.OS;
    Object.defineProperty(Platform, 'OS', { configurable: true, get: () => 'android' });
    try {
      const { rerender } = render(nested(false));
      rerender(nested(true));
      expect(isOpen('sheet')).toBe(false);
    } finally {
      Object.defineProperty(Platform, 'OS', { configurable: true, get: () => original });
    }
  });

  it('still calls the caller\'s own onShow and onDismiss', () => {
    const onShow = jest.fn();
    const onDismiss = jest.fn();
    render(
      <AppModal testID="sheet" visible onShow={onShow} onDismiss={onDismiss}>
        <Text>sheet</Text>
      </AppModal>
    );
    finishShow('sheet');
    finishDismiss('sheet');
    expect(onShow).toHaveBeenCalledTimes(1);
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('keeps half-typed text held by the form when the sheets come back after unlock', () => {
    function Form() {
      const [limit, setLimit] = useState('');
      return (
        <AppModal testID="sheet" visible>
          <TextInput
            testID="limit"
            accessibilityLabel="Monthly limit"
            accessibilityHint="The most to spend in a month"
            value={limit}
            onChangeText={setLimit}
          />
          <AppModal testID="picker" visible>
            <Text>picker</Text>
          </AppModal>
        </AppModal>
      );
    }
    const tree = (covered: boolean) => (
      <AppCoveredProvider value={covered}>
        <Form />
      </AppCoveredProvider>
    );
    const { rerender } = render(tree(false));
    fireEvent.changeText(screen.getByTestId('limit'), '4321');

    rerender(tree(true));
    finishDismiss('picker');
    finishDismiss('sheet');
    rerender(tree(false));
    finishShow('sheet');

    expect(screen.getByTestId('limit').props.value).toBe('4321');
    expect(isOpen('picker')).toBe(true);
  });
});
