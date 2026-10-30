import { File } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { AppState, Platform } from 'react-native';
import { withShareSuppression } from './appLock';

/**
 * How long to keep waiting for the share call once the app is back in front, on Android. The OS
 * hands the share sheet's result back before the app resumes, so a call that is going to settle
 * has normally done so by then; this only covers the case where it never does.
 */
export const SHARE_RETURN_GRACE_MS = 1_500;

/**
 * The "check availability, share via the OS sheet, always clean up the cache file afterwards"
 * pattern shared by statementImportsApi.downloadFile, supportApi.downloadAttachment, and
 * reportExport.ts's shareCsv/sharePdf -- extracted after three independent, byte-for-byte copies
 * of it turned up in review (Track D security cleanup).
 *
 * `file` must already exist and hold the bytes to share. This always deletes it -- on a
 * successful share, a thrown share, or sharing being unavailable -- because in every one of those
 * three call sites the file existed purely to hand the share sheet a real URI and has no reason
 * to survive the attempt either way.
 */
export async function shareFileAndCleanUp(file: File, options: Sharing.SharingOptions): Promise<void> {
  try {
    if (!(await Sharing.isAvailableAsync())) {
      throw new Error('Sharing is not available on this device.');
    }
    // D5 (Track D security cleanup) -- see appLock.ts's withShareSuppression doc comment.
    await withShareSuppression(() => settleOnReturn(Sharing.shareAsync(file.uri, options)));
  } finally {
    // D2 (Track D security cleanup, docs/project-management/plans/mobile-correctness-trust-roadmap.md).
    // Best-effort: a failed cleanup must not turn a completed (or already-failed) share into a new
    // user-facing error -- sweepFileCache's own startup/sign-out sweep is the backstop if this
    // doesn't run.
    try {
      file.delete();
    } catch {
      // See comment above.
    }
  }
}

/**
 * On Android, stops waiting for the share call once the user has left the app for the share sheet,
 * come back, and SHARE_RETURN_GRACE_MS has passed without it settling. expo-sharing settles it only
 * when the sheet's activity result reaches the app; if that result is lost, the call never settles,
 * and every caller that awaits it (an export button showing "Preparing…") would wait for good.
 * Returning to the app means the sheet is closed, so the share is over either way.
 *
 * iOS settles the call from the sheet's own completion handler, so it is left as it is there.
 */
function settleOnReturn(share: Promise<void>): Promise<void> {
  if (Platform.OS !== 'android') return share;
  return new Promise<void>((resolve, reject) => {
    let leftApp = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const stop = () => {
      subscription.remove();
      clearTimeout(timer);
    };
    const subscription = AppState.addEventListener('change', (next) => {
      clearTimeout(timer);
      timer = undefined;
      if (next !== 'active') {
        leftApp = true;
      } else if (leftApp) {
        timer = setTimeout(() => {
          stop();
          resolve();
        }, SHARE_RETURN_GRACE_MS);
      }
    });
    share.then(
      () => {
        stop();
        resolve();
      },
      (error: unknown) => {
        stop();
        reject(error);
      },
    );
  });
}
