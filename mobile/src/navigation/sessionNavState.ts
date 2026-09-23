import type { NavigationState } from '@react-navigation/native';

// Module memory, deliberately NOT AsyncStorage: it has to outlive RootNavigator being unmounted and
// remounted inside one running process (AppLockGate replaces the whole app tree with its lock
// screen, then mounts a fresh navigator after unlock), and it must NOT outlive the process -- a
// user who clears the app from recents starts on Home, by product decision. Nothing here ever
// reaches disk, so there is no stale-schema or shared-device leak to manage.
let saved: NavigationState | undefined;

// A remount REPLAYS whatever params are restored: screens consume one-shot params through local
// state that starts empty again (Import would re-run a shared-file import and re-enter a
// password-carrying re-import; Home would re-open Add Transaction; VerifyEmailChange would replay a
// spent one-time token). So params are dropped by default, and only these two -- plain ids, no
// side effect on mount, and unable to render at all without them -- are kept. A new screen's
// params are therefore stripped until someone decides otherwise, rather than replayed until
// someone remembers to exclude them.
const PARAMS_SAFE_TO_RESTORE = new Set(['SupportTicketDetail', 'SettingsBankSyncConfirm']);

type NavNode = { name: string; params?: unknown; state?: NavTree } & Record<string, unknown>;
type NavTree = { routes: NavNode[] } & Record<string, unknown>;

function restorable(tree: NavTree): NavTree {
  return {
    ...tree,
    routes: tree.routes.map(({ params, state, ...route }) => ({
      ...route,
      ...(params !== undefined && PARAMS_SAFE_TO_RESTORE.has(route.name) ? { params } : {}),
      ...(state ? { state: restorable(state) } : {}),
    })),
  };
}

export function getSessionNavState(): NavigationState | undefined {
  return saved;
}

export function saveSessionNavState(state: NavigationState | undefined): void {
  saved = state ? (restorable(state as unknown as NavTree) as unknown as NavigationState) : undefined;
}

/** Called wherever the signed-in session ends or the tree is reset, so the next mount starts on Home. */
export function clearSessionNavState(): void {
  saved = undefined;
}
