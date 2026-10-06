import { Platform } from 'react-native';

/** Message only, never the error object: nothing here should put a token or payload in a log. */
function logPushFailure(context: string, error: unknown): void {
  const message = error instanceof Error ? error.message : 'unknown error';
  console.warn(`[systemNotification] ${context}`, { message });
}

// Posts a real SYSTEM notification from inside the app: the kind the phone itself draws, that slides
// in at the top, and that stays in the notification centre after a swipe so it can be read again.
//
// Why this exists: while the app is open the phone never shows a push on its own (FCM is silent in
// the foreground on both platforms), so a campaign message used to be drawn by the app (AppBanner)
// and was gone for good once swiped away. Posting it as a local notification is how other apps get
// the swipe-to-notification-centre behaviour.
//
// The native piece (react-native-notify-kit) only exists in builds made after it was added, but
// over-the-air JavaScript reaches every build on the same runtime version, old ones included. So
// the library is loaded lazily, inside a try/catch, and every failure -- module missing in an
// old build, notifications switched off, anything else -- is reported as "not shown" so the caller
// can fall back to the in-app banner instead of losing the message.
//
// A lazy require(), not import(): both load the module only when first needed and both throw
// inside the try when the native side is missing, but under this repo's Jest setup (vm-modules) a
// dynamic import() bypasses jest.mock and loads the real library, so it could not be tested.

/** The Android channel campaign messages use. HIGH importance is what makes it slide in at the top. */
export const CAMPAIGN_CHANNEL_ID = 'campaigns';
const CAMPAIGN_CHANNEL_NAME = 'Announcements';

type NotifyKit = typeof import('react-native-notify-kit');

function loadNotifyKit(): NotifyKit | null {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    return require('react-native-notify-kit') as NotifyKit;
  } catch (error) {
    // An old build with no native module lands here; that is expected, not an error to alarm on.
    logPushFailure('react-native-notify-kit is not available in this build', error);
    return null;
  }
}

/**
 * Shows `title` / `body` as a system notification.
 *
 * @param id optional stable id (the push's own message id): the same push shown twice replaces
 *     itself instead of stacking.
 * @returns true if the system accepted it; false if it could not be shown, in which case nothing
 *     was posted and the caller should show its own banner. Never throws.
 */
export async function showSystemNotification(title: string, body: string, id?: string): Promise<boolean> {
  try {
    const kit = loadNotifyKit();
    if (!kit) return false;
    const notifee = kit.default;

    // Switched off in the phone's settings (or never granted): the system would drop it silently.
    const settings = await notifee.getNotificationSettings();
    if (settings.authorizationStatus !== kit.AuthorizationStatus.AUTHORIZED
      && settings.authorizationStatus !== kit.AuthorizationStatus.PROVISIONAL) {
      return false;
    }

    let channelId: string | undefined;
    if (Platform.OS === 'android') {
      // Idempotent: creating a channel that already exists leaves it as it is.
      channelId = await notifee.createChannel({
        id: CAMPAIGN_CHANNEL_ID,
        name: CAMPAIGN_CHANNEL_NAME,
        importance: kit.AndroidImportance.HIGH,
      });
    }

    await notifee.displayNotification({
      id,
      title,
      body,
      android: channelId ? { channelId, pressAction: { id: 'default' } } : undefined,
      // Without these the system hides an iOS notification while the app is open. banner = the slide-in,
      // list = keep it in the notification centre. No sound or badge: it is an announcement.
      ios: { foregroundPresentationOptions: { banner: true, list: true, sound: false, badge: false } },
    });
    return true;
  } catch (error) {
    logPushFailure('showSystemNotification failed', error);
    return false;
  }
}

/**
 * Registers the (empty) handler the library asks for at start-up. A notification can be tapped when
 * the app is not in front; the library warns and may drop the event if no handler exists. Tapping
 * just opens the app, which the system does by itself, so there is nothing to do in the handler.
 * Safe to call in any build: it does nothing where the native module is absent.
 */
export async function registerSystemNotificationEvents(): Promise<void> {
  try {
    const kit = loadNotifyKit();
    kit?.default.onBackgroundEvent(async () => {});
  } catch (error) {
    logPushFailure('registerSystemNotificationEvents failed', error);
  }
}
