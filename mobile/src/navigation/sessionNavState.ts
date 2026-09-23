import type { NavigationState } from '@react-navigation/native';

// Module memory, deliberately NOT AsyncStorage: it has to outlive RootNavigator being unmounted and
// remounted inside one running process (AppLockGate replaces the whole app tree with its lock
// screen, then mounts a fresh navigator after unlock), and it must NOT outlive the process -- a
// user who clears the app from recents starts on Home, by product decision. Nothing here ever
// reaches disk, so there is no stale-schema, params-stripping or shared-device leak to manage.
let saved: NavigationState | undefined;

export function getSessionNavState(): NavigationState | undefined {
  return saved;
}

export function saveSessionNavState(state: NavigationState | undefined): void {
  saved = state;
}

/** Called wherever the signed-in session ends or the tree is reset, so the next mount starts on Home. */
export function clearSessionNavState(): void {
  saved = undefined;
}
