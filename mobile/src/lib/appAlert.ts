import type { AlertButton, AlertOptions } from 'react-native';

// react-native's Alert.alert is a NATIVE dialog: it floats above the whole React Native root, so
// AppLockGate's lock overlay cannot cover it, and there is no API to close one that is already
// open. An alert left open when the app locks would therefore sit on top of the lock screen with
// whatever it says ("Delete this account?", a notification's body). AppAlert is the same call, drawn
// by React Native instead (see AppAlertOverlay), so it hides with the rest of the app while locked
// and is still there after unlock. eslint.config.js forbids importing Alert from react-native.
//
// It also queues: Alert.alert does not (a second call while one is open is simply another native
// dialog), so callers used to hand-roll a queue -- here an alert raised while another is showing
// waits its turn.
//
// WHERE it is drawn: normally in-tree, at the root (ROOT_ALERT_CONTAINER). But a native <Modal> is
// above anything in-tree, so while an AppModal is open the alert is drawn INSIDE the topmost one
// instead -- never as a second, sibling native modal, which iOS does not reliably present on top of
// a modal that is already up.

export const ROOT_ALERT_CONTAINER = 'root';

export type AppAlertEntry = {
  id: number;
  title: string;
  message?: string;
  buttons: AlertButton[];
  options?: AlertOptions;
};

let queue: AppAlertEntry[] = [];
let nextId = 1;
// Ids of the AppModals currently on screen, oldest first.
let openContainers: string[] = [];
const listeners = new Set<() => void>();

function emit(): void {
  listeners.forEach((listener) => listener());
}

export const AppAlert = {
  /** Same signature as react-native's Alert.alert, so a call site only changes its import. */
  alert(title: string, message?: string, buttons?: AlertButton[], options?: AlertOptions): void {
    queue = [
      ...queue,
      { id: nextId++, title, message, buttons: buttons && buttons.length > 0 ? buttons : [{ text: 'OK' }], options },
    ];
    emit();
  },
};

export function subscribeAppAlerts(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** The alert to show right now (the head of the queue), or undefined. Stable between changes. */
export function getCurrentAppAlert(): AppAlertEntry | undefined {
  return queue[0];
}

/** Which container should draw the current alert: the topmost open AppModal, else the root. */
export function getTopAlertContainer(): string {
  return openContainers.length > 0 ? openContainers[openContainers.length - 1] : ROOT_ALERT_CONTAINER;
}

/** Called by an AppModal while it is on screen. Returns the function that unregisters it. */
export function registerAlertContainer(id: string): () => void {
  openContainers = [...openContainers, id];
  emit();
  return () => {
    openContainers = openContainers.filter((existing) => existing !== id);
    emit();
  };
}

/** Removes the alert being shown and returns it. */
export function dismissCurrentAppAlert(): AppAlertEntry | undefined {
  const [head, ...rest] = queue;
  queue = rest;
  emit();
  return head;
}

/**
 * A dismissal that is not a button press (backdrop tap, Android back). Does nothing to a
 * non-cancelable alert; otherwise closes it and calls options.onDismiss, as Alert.alert does.
 */
export function dismissCurrentAppAlertByUser(): void {
  const head = queue[0];
  if (!head || head.options?.cancelable === false) return;
  dismissCurrentAppAlert();
  head.options?.onDismiss?.();
}

/**
 * Android's back button, for a container that may be showing the alert. Returns true when it was
 * consumed by the alert (dismissing it if cancelable, or ignoring the press if not) -- so back
 * never reaches the sheet or screen underneath an alert that is up.
 */
export function handleAlertBack(containerId: string): boolean {
  if (!queue[0] || getTopAlertContainer() !== containerId) return false;
  dismissCurrentAppAlertByUser();
  return true;
}

/**
 * Drops every alert, showing or queued. Called when a session ends: an alert belongs to the session
 * that raised it, and one left behind would greet whoever signs in next on a shared phone -- and
 * its button would act with the departed session's credentials.
 */
export function clearAppAlerts(): void {
  if (queue.length === 0) return;
  queue = [];
  emit();
}

export function __resetAppAlertForTests(): void {
  queue = [];
  openContainers = [];
  emit();
}
