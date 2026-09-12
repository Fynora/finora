import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from 'react';
import { Toast } from '../components/Toast';

interface ToastState { title: string; body?: string }
interface ToastContextValue { showToast: (title: string, body?: string) => void }

const ToastContext = createContext<ToastContextValue | null>(null);

const AUTO_DISMISS_MS = 3000;

/**
 * A single toast at a time -- the mockup's own examples ("Goal Created", "Statement Imported")
 * are one-off success confirmations, never a queue. A later showToast call while one is visible
 * simply replaces it and restarts the 3s timer, rather than stacking.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const showToast = useCallback((title: string, body?: string) => {
    if (timerRef.current) clearTimeout(timerRef.current);
    setToast({ title, body });
    timerRef.current = setTimeout(() => setToast(null), AUTO_DISMISS_MS);
  }, []);

  return (
    <ToastContext.Provider value={{ showToast }}>
      {children}
      {toast ? <Toast title={toast.title} body={toast.body} /> : null}
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside a ToastProvider');
  return ctx;
}
