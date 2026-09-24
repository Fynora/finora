import { createContext, useContext, useEffect, useId } from 'react';
import { Modal, type ModalProps } from 'react-native';
import { AppAlertOverlay } from './AppAlertOverlay';
import { handleAlertBack, registerAlertContainer } from '../lib/appAlert';

// True while AppLockGate is covering the app (the lock screen, or the split second before it
// knows whether to lock). React Native's <Modal> is presented in its own NATIVE layer, above the
// whole React Native root -- so a JS lock overlay cannot cover it. AppLockGate keeps the app
// mounted under its lock screen (so an unlock returns to the same screen with its state), which
// means a sheet left open when the lock engages would otherwise stay visible, and tappable, on top
// of the lock screen. Every Modal in the app therefore goes through AppModal.
const AppCoveredContext = createContext(false);
export const AppCoveredProvider = AppCoveredContext.Provider;

/** True while the app is covered by the lock screen (or the moment before it knows whether to lock). */
export function useAppCovered(): boolean {
  return useContext(AppCoveredContext);
}

/**
 * Drop-in replacement for react-native's Modal. Hidden (not just visually covered) while the app
 * is locked; the component that renders it stays mounted, so its state -- including half-typed
 * text held in it -- is still there when the modal comes back after unlock.
 *
 * It is also where an AppAlert is drawn while this modal is the topmost one open: an alert raised
 * from inside a sheet ("Delete this transaction?") has to appear ABOVE the sheet, and the only
 * thing above a native modal is more content inside it. Android's back button is routed to that
 * alert first, so it never closes the sheet underneath.
 *
 * Use this, not Modal, everywhere: eslint.config.js forbids importing Modal directly.
 */
export function AppModal({ children, onRequestClose, ...props }: ModalProps) {
  const covered = useAppCovered();
  const id = useId();
  const visible = (props.visible ?? true) && !covered;

  useEffect(() => {
    if (!visible) return;
    return registerAlertContainer(id);
  }, [visible, id]);

  return (
    <Modal
      {...props}
      visible={visible}
      onRequestClose={(event) => {
        if (handleAlertBack(id)) return;
        onRequestClose?.(event);
      }}
    >
      {children}
      <AppAlertOverlay containerId={id} />
    </Modal>
  );
}
