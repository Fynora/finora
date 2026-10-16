import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
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
 * never replaces an error still showing. An error ('error' tone) shows without the tick.
 *
 * A toast raised while the cold-start launch animation still covers the app is held, and its 3s
 * only start once the animation has gone -- otherwise most of it would run out unseen behind it.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);
  const launching = useLaunchCovering();

  const showToast = useCallback((title: string, body?: string, tone?: ToastTone) => {
    // A new object every call, so the timer effect below restarts even for an identical message.
    // An error still showing outranks a success: with two saves in flight, the second succeeding
    // must not wipe the first's failure off the screen before it can be read. Keeping the same
    // object lets the error's own 3 seconds run on.
    setToast((prev) => (prev?.tone === 'error' && tone !== 'error' ? prev : { title, body, tone }));
  }, []);

  useEffect(() => {
    if (!toast || launching) return;
    const timer = setTimeout(() => setToast(null), AUTO_DISMISS_MS);
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
