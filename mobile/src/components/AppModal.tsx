import { createContext, useContext } from 'react';
import { Modal, type ModalProps } from 'react-native';

// True while AppLockGate is covering the app (the lock screen, or the split second before it
// knows whether to lock). React Native's <Modal> is presented in its own NATIVE layer, above the
// whole React Native root -- so a JS lock overlay cannot cover it. AppLockGate keeps the app
// mounted under its lock screen (so an unlock returns to the same screen with its state), which
// means a sheet left open when the lock engages would otherwise stay visible, and tappable, on top
// of the lock screen. Every Modal in the app therefore goes through AppModal.
const AppCoveredContext = createContext(false);
export const AppCoveredProvider = AppCoveredContext.Provider;

/**
 * Drop-in replacement for react-native's Modal. Hidden (not just visually covered) while the app
 * is locked; the component that renders it stays mounted, so its state -- including half-typed
 * text held in it -- is still there when the modal comes back after unlock.
 *
 * Use this, not Modal, everywhere: eslint.config.js forbids importing Modal directly.
 */
export function AppModal(props: ModalProps) {
  const covered = useContext(AppCoveredContext);
  return <Modal {...props} visible={(props.visible ?? true) && !covered} />;
}
