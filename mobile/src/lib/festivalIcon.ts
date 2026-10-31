import { AppState, Platform } from 'react-native';
import { requireOptionalNativeModule } from 'expo';
import { rawApi, type ApiEnvelope } from '../api/client';
import { reportHandledEvent } from './monitoring';

/**
 * Festival app icon: the home-screen icon follows the schedule an admin sets in the admin portal
 * ("Festival App Icon"), served by the public GET /app/icon.
 *
 * A phone can only show an icon that is bundled in the installed app, so the server's keys map to
 * the icons this build carries (see the expo-alternate-app-icons entry in app.config.ts). A key this
 * build does not know -- an icon added on the server for a later app version -- is treated as the
 * standard icon rather than ignored, so an old build never stays stuck on a festival icon.
 *
 * When the icon is switched differs by platform, on purpose:
 * - Android switches by enabling a different launcher entry. On some launchers that restarts the
 *   app or moves its home-screen shortcut, so it is done as the app goes to the background, never
 *   while someone is using it.
 * - iOS only allows the switch while the app is open, and always shows its own "You have changed
 *   the icon" alert, so it is done when the answer arrives with the app in the foreground.
 *
 * A failed or unreadable answer changes nothing: the phone keeps whatever icon it has. Going back to
 * the standard icon happens only when the server says so.
 */

/**
 * The expo-alternate-app-icons native module, read directly and optionally rather than through the
 * package's JS entry (a thin pass-through to the same three members). The package entry calls
 * requireNativeModule at import time, which throws in a binary built before this module was added;
 * an over-the-air update carrying this file to such a binary (1.2.0 builds already exist) would
 * then crash the app at start. Optional means that binary just never switches icons.
 */
interface AlternateIconsModule {
  supportsAlternateIcons: boolean;
  getAppIconName(): string | null;
  setAlternateAppIcon(name: string | null): Promise<string | null>;
}

let iconsModule: AlternateIconsModule | null | undefined;
function alternateIcons(): AlternateIconsModule | null {
  if (iconsModule === undefined) {
    iconsModule = requireOptionalNativeModule<AlternateIconsModule>('ExpoAlternateAppIcons');
  }
  return iconsModule;
}

/** Test seam: forget the cached module lookup. */
export function resetAlternateIconsModuleForTests(): void {
  iconsModule = undefined;
}

/** Server key to the icon name bundled in this build. The standard icon is `null`. */
export const BUNDLED_ICONS: Readonly<Record<string, string>> = {
  diwali: 'Diwali',
};

interface AppIconAnswer {
  icon: string;
  until?: string;
}

/** The bundled icon name the server wants now (`null` = standard), or `undefined` if it could not say. */
export async function fetchWantedIcon(): Promise<string | null | undefined> {
  try {
    // rawApi: the answer is public, so no session, no token refresh and no sign-out on a 401.
    const response = await rawApi.get<ApiEnvelope<AppIconAnswer>>('/app/icon', { timeout: 10_000 });
    const key = response.data?.data?.icon;
    if (typeof key !== 'string') return undefined;
    return Object.prototype.hasOwnProperty.call(BUNDLED_ICONS, key) ? BUNDLED_ICONS[key] : null;
  } catch {
    return undefined;
  }
}

/** Switches the icon if it differs from `wanted`. Never throws. */
export async function applyIcon(wanted: string | null): Promise<void> {
  try {
    const icons = alternateIcons();
    if (!icons?.supportsAlternateIcons) return;
    if (icons.getAppIconName() === wanted) return;
    await icons.setAlternateAppIcon(wanted);
  } catch (error) {
    reportHandledEvent('Festival app icon switch failed', 'festival_icon', {
      wanted: wanted ?? 'default',
      error: error instanceof Error ? error.message : String(error),
    });
  }
}

/**
 * Starts following the schedule: asks the server at launch and each time the app comes back, and
 * switches at the moment the platform allows (see the module comment). Returns the unsubscribe.
 */
export function startFestivalIcon(): () => void {
  let wanted: string | null | undefined;
  // React Native types currentState as a plain string (or null before the first state is known).
  let current: string | null | undefined = AppState.currentState;

  const refresh = async () => {
    const answer = await fetchWantedIcon();
    if (answer === undefined) return;
    wanted = answer;
    if (Platform.OS === 'ios' && AppState.currentState === 'active') await applyIcon(answer);
  };

  void refresh();

  const subscription = AppState.addEventListener('change', (next) => {
    const previous = current;
    current = next;
    if (next === 'active' && previous !== 'active') {
      void refresh();
    } else if (Platform.OS === 'android' && next === 'background' && wanted !== undefined) {
      void applyIcon(wanted);
    }
  });
  return () => subscription.remove();
}
