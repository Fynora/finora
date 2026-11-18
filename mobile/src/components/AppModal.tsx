import { createContext, useCallback, useContext, useEffect, useId, useMemo, useRef, useState } from 'react';
import { Modal, Platform, View, type ModalProps } from 'react-native';
import { AppAlertOverlay, useAlertShowing } from './AppAlertOverlay';
import { AppBannerOverlay } from './AppBannerOverlay';
import { StyleSheet } from 'react-native';
import { handleAlertBack, registerAlertContainer } from '../lib/appAlert';

// True while AppLockGate is covering the app (the lock screen, or the split second before it
// knows whether to lock). React Native's <Modal> is presented in its own NATIVE layer, above the
// whole React Native root -- so a JS lock overlay cannot cover it. AppLockGate keeps the app
// mounted under its lock screen (so an unlock returns to the same screen with its state), which
// means a sheet left open when the lock engages would otherwise stay visible, and tappable, on top
// of the lock screen. Every Modal in the app therefore goes through AppModal.
const AppCoveredContext = createContext(false);
export const AppCoveredProvider = AppCoveredContext.Provider;

// True while App.tsx's cold-start LaunchAnimation covers the app. Same native-layer problem as the
// lock screen: a sheet or the onboarding tour that opens on mount would otherwise be drawn on top
// of the launch animation. A separate context because App.tsx sits outside AppLockGate.
const LaunchCoveredContext = createContext(false);
export const LaunchCoveredProvider = LaunchCoveredContext.Provider;

/** True while the cold-start launch animation covers the app (only that, not the lock screen). */
export function useLaunchCovering(): boolean {
  return useContext(LaunchCoveredContext);
}

/**
 * True while the app is covered by the lock screen (or the moment before it knows whether to lock),
 * or by the cold-start launch animation.
 */
export function useAppCovered(): boolean {
  const locked = useContext(AppCoveredContext);
  const launching = useContext(LaunchCoveredContext);
  return locked || launching;
}

/** How long an inner modal that was unmounted (so sends no dismiss event) gets to slide away. */
export const NESTED_MODAL_DISMISS_MS = 350;

/** How long to wait for iOS to report a modal shown or dismissed before carrying on without it. */
export const MODAL_DISMISS_FALLBACK_MS = 700;

type ReleaseHold = (afterMs: number) => void;

interface ParentModal {
  /** Whether the caller and the lock still want this modal open. */
  open: boolean;
  /** Coming back after the app was covered: inner modals wait until it is on screen again. */
  restoring: boolean;
  /** Keeps this modal presented until the returned function is called. */
  hold: () => ReleaseHold;
}

const ParentModalContext = createContext<ParentModal | null>(null);

/**
 * Drop-in replacement for react-native's Modal. Hidden (not just visually covered) while the app
 * is locked; the component that renders it stays mounted, so its state -- including half-typed
 * text held in it -- is still there when the modal comes back after unlock.
 *
 * A modal inside another modal always closes first, and comes back second. On iOS, dismissing a
 * view controller that has another one presented on top of it removes only the top one, so closing
 * both in one commit left the outer controller on screen, empty and invisible, swallowing every
 * touch (the PIN pad's included) until the app was killed. Each modal therefore stays presented
 * until the modals inside it have gone.
 *
 * It is also where an AppAlert is drawn while this modal is the topmost one open: an alert raised
 * from inside a sheet ("Delete this transaction?") has to appear ABOVE the sheet, and the only
 * thing above a native modal is more content inside it. Android's back button is routed to that
 * alert first, so it never closes the sheet underneath.
 *
 * Use this, not Modal, everywhere: eslint.config.js forbids importing Modal directly.
 */
export function AppModal({ children, onRequestClose, onShow, onDismiss, animationType, ...props }: ModalProps) {
  const covered = useAppCovered();
  const parent = useContext(ParentModalContext);
  const id = useId();
  const visible = (props.visible ?? true) && !covered && (parent?.open ?? true);
  const alertUp = useAlertShowing(id);

  // Whether the native modal is on screen, which outlasts `visible` while it closes.
  const [presented, setPresented] = useState(false);
  // How many modals inside this one are still on screen.
  const [holds, setHolds] = useState(0);
  const [restoring, setRestoring] = useState(false);

  const nativeVisible = (visible && !parent?.restoring) || (presented && holds > 0);

  // Adjusted during render, so a modal never commits one frame in the wrong state.
  if (nativeVisible && !presented) setPresented(true);
  // Android has no dismiss event, and its dialog closes as soon as it is told to.
  if (!nativeVisible && presented && Platform.OS !== 'ios') setPresented(false);
  if (covered && presented && !restoring) setRestoring(true);
  if (visible && parent?.restoring && !restoring) setRestoring(true);
  if (!covered && !visible && restoring) setRestoring(false);

  // iOS drops the dismiss event if the view goes away first.
  useEffect(() => {
    if (!presented || nativeVisible) return;
    const timer = setTimeout(() => setPresented(false), MODAL_DISMISS_FALLBACK_MS);
    return () => clearTimeout(timer);
  }, [presented, nativeVisible]);

  // The show event never comes for a modal that was still up when the app was uncovered.
  useEffect(() => {
    if (!restoring || covered || !nativeVisible) return;
    const timer = setTimeout(() => setRestoring(false), MODAL_DISMISS_FALLBACK_MS);
    return () => clearTimeout(timer);
  }, [restoring, covered, nativeVisible]);

  const [releaseTimers] = useState(() => new Set<ReturnType<typeof setTimeout>>());
  useEffect(() => () => releaseTimers.forEach(clearTimeout), [releaseTimers]);

  const hold = useCallback((): ReleaseHold => {
    setHolds((n) => n + 1);
    let released = false;
    return (afterMs) => {
      if (released) return;
      released = true;
      if (afterMs <= 0) {
        setHolds((n) => n - 1);
        return;
      }
      const timer = setTimeout(() => {
        releaseTimers.delete(timer);
        setHolds((n) => n - 1);
      }, afterMs);
      releaseTimers.add(timer);
    };
  }, [releaseTimers]);

  // Holds the modal around this one open for as long as this one is on screen.
  const holdParent = parent?.hold;
  const releaseParent = useRef<ReleaseHold | null>(null);
  useEffect(() => {
    if (presented && holdParent && !releaseParent.current) releaseParent.current = holdParent();
    if (!presented && releaseParent.current) {
      releaseParent.current(0);
      releaseParent.current = null;
    }
  }, [presented, holdParent]);
  // Unmounted while on screen: no dismiss event will come, so allow for the slide-out.
  useEffect(() => () => {
    releaseParent.current?.(NESTED_MODAL_DISMISS_MS);
    releaseParent.current = null;
  }, []);

  const asParent = useMemo(() => ({ open: visible, restoring, hold }), [visible, restoring, hold]);

  // Registered only once actually on screen, so an inner modal coming back after the outer one
  // ends up on top of it in the alert order.
  const onScreen = visible && nativeVisible;
  useEffect(() => {
    if (!onScreen) return;
    return registerAlertContainer(id);
  }, [onScreen, id]);

  return (
    <Modal
      {...props}
      visible={nativeVisible}
      // No slide over the lock screen on the way out, and none on the way back after unlock: the
      // app returns to where it was.
      animationType={covered || restoring ? 'none' : animationType}
      onShow={(event) => {
        setRestoring(false);
        onShow?.(event);
      }}
      onDismiss={() => {
        setPresented(false);
        onDismiss?.();
      }}
      onRequestClose={(event) => {
        if (handleAlertBack(id)) return;
        onRequestClose?.(event);
      }}
    >
      {/* Layout-neutral (Modal's own container is flex: 1 too). Hidden from screen readers and touch
          while this modal is showing an alert: the alert is in the tree, not a native dialog that
          traps focus, so the sheet behind it would otherwise stay reachable. */}
      <View
        style={styles.fill}
        pointerEvents={alertUp ? 'none' : 'auto'}
        importantForAccessibility={alertUp ? 'no-hide-descendants' : 'auto'}
        accessibilityElementsHidden={alertUp}
      >
        <ParentModalContext.Provider value={asParent}>{children}</ParentModalContext.Provider>
      </View>
      <AppAlertOverlay containerId={id} />
      {/* A passing banner (an admin campaign push) is drawn here while this is the topmost modal,
          for the same reason as the alert: nothing in the tree can show above a native modal. */}
      <AppBannerOverlay containerId={id} />
    </Modal>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
});
