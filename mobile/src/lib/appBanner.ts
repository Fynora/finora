// A small top-of-screen banner for a message that should be seen but must not interrupt: an admin
// campaign push that arrives while the app is open. AppAlert (see ./appAlert) is the opposite -- a
// blocking dialog the user has to dismiss, right for a due-date warning and wrong for an
// announcement in the middle of someone's work.
//
// Drawn in-tree by AppBannerOverlay (not by the OS), for the same reason as AppAlert: it hides with
// the rest of the app while the app is locked, so a push's text can never show over the lock screen.
//
// One banner at a time: a newer message replaces the one on screen instead of queueing behind it. A
// stack of banners that each wait their turn would keep announcing old news long after it mattered.

export type AppBannerEntry = {
  id: number;
  title: string;
  message: string;
};

/** How long a banner stays before it goes away on its own. */
export const BANNER_DURATION_MS = 6000;

let current: AppBannerEntry | undefined;
let nextId = 1;
let timer: ReturnType<typeof setTimeout> | undefined;
const listeners = new Set<() => void>();

function emit(): void {
  listeners.forEach((listener) => listener());
}

function clearTimer(): void {
  if (timer !== undefined) {
    clearTimeout(timer);
    timer = undefined;
  }
}

export const AppBanner = {
  /** Shows the banner, replacing any already showing, and removes it after BANNER_DURATION_MS. */
  show(title: string, message: string): void {
    clearTimer();
    current = { id: nextId++, title, message };
    emit();
    timer = setTimeout(() => AppBanner.dismiss(), BANNER_DURATION_MS);
  },
  dismiss(): void {
    clearTimer();
    if (current === undefined) return;
    current = undefined;
    emit();
  },
};

export function subscribeAppBanner(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** The banner showing right now, or undefined. Stable between changes. */
export function getCurrentAppBanner(): AppBannerEntry | undefined {
  return current;
}

/** Test seam: drops the banner and its timer so one test cannot leak into the next. */
export function __resetAppBannerForTests(): void {
  clearTimer();
  current = undefined;
  nextId = 1;
  listeners.clear();
}
