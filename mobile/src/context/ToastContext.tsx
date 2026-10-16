import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { AccessibilityInfo, Platform } from 'react-native';
import { useLaunchCovering } from '../components/AppModal';
import { Toast, type ToastTone } from '../components/Toast';

interface ToastState { title: string; body?: string; tone?: ToastTone }
interface ToastContextValue { showToast: (title: string, body?: string, tone?: ToastTone) => void }

const ToastContext = createContext<ToastContextValue | null>(null);

const AUTO_DISMISS_MS = 3000;

/**
 * A single toast at a time -- the mockup's own examples ("Goal Created", "Statement Imported")
 * are one-off success confirmations, never a queue. A later showToast call while one is visible
 * simply replaces it and restarts the 3s timer, rather than stacking -- except that a success
 * never replaces an error still showing: it waits, and shows once the error's time is up. An error
 * ('error' tone) shows without the tick.
 *
 * On iOS each toast is also read out to VoiceOver as it appears; the toast's live region does
 * that on Android only.
 *
 * A toast raised while the cold-start launch animation still covers the app is held, and its 3s
 * only start once the animation has gone -- otherwise most of it would run out unseen behind it.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);
  const launching = useLaunchCovering();
  // A success that arrived while an error showed, to show once the error's time is up.
  const heldSuccess = useRef<ToastState | null>(null);

  const showToast = useCallback((title: string, body?: string, tone?: ToastTone) => {
    // A new object every call, so the timer effect below restarts even for an identical message.
    const next: ToastState = { title, body, tone };
    setToast((prev) => {
      // An error still showing outranks a success: with two saves in flight, the second succeeding
      // must not wipe the first's failure off the screen before it can be read. Keeping the same
      // object lets the error's own 3 seconds run on. The success is held, not dropped: a failed
      // save retried at once can succeed while its failure still shows, and dropping that left
      // "Could not save" as the last word on a save that had worked.
      if (prev?.tone === 'error' && tone !== 'error') {
        heldSuccess.current = next;
        return prev;
      }
      // A later error is the newer news; a success held behind the earlier one is stale now.
      heldSuccess.current = null;
      return next;
    });
  }, []);

  useEffect(() => {
    if (!toast || launching) return;
    if (Platform.OS === 'ios') {
      AccessibilityInfo.announceForAccessibility(toast.body ? `${toast.title}. ${toast.body}` : toast.title);
    }
    const timer = setTimeout(() => {
      const held = heldSuccess.current;
      heldSuccess.current = null;
      setToast(held);
    }, AUTO_DISMISS_MS);
    return () => clearTimeout(timer);
  }, [toast, launching]);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      {toast && !launching ? <Toast title={toast.title} body={toast.body} tone={toast.tone} /> : null}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside a ToastProvider');
  return ctx;
}
