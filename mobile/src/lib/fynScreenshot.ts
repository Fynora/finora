import * as DocumentPicker from 'expo-document-picker';
import * as appLock from './appLock';
import type { RNFile } from '../api/endpoints';

/**
 * Picking a screenshot to attach to a Fyn chat message -- same shape as pickTicketAttachment()
 * (lib/ticketAttachment.ts) for the same reason: RNFile is what fynChatApi.sendScreenshot's
 * FormData upload takes, and expo-document-picker already returns almost exactly that.
 *
 * Mirrors FynScreenshotOcrService's own allow-list and size ceiling on the backend (PNG, JPEG, or
 * WebP; 8 MB) -- this is a convenience so most users never hit the server's 400 at all, not a
 * substitute for it: the server re-validates every byte regardless of what the picker let through.
 */

const MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024;
const ACCEPTED_MIME = ['image/png', 'image/jpeg', 'image/webp'];

export class ScreenshotTooLargeError extends Error {}

/** Returns null when the user dismisses the picker -- a cancel is not an error. Throws only for
 *  a genuinely unusable selection (over size), so the caller can show that message. */
export async function pickFynScreenshot(): Promise<RNFile | null> {
  // Same reasoning as pickTicketAttachment(): the native picker backgrounds this app, and
  // without this suppression AppLockGate would show a spurious lock prompt the instant the
  // picker (or a provider like Photos/Files) returns focus here.
  const result = await appLock.withShareSuppression(() => DocumentPicker.getDocumentAsync({
    type: ACCEPTED_MIME,
    // Same reasoning as pickTicketAttachment()/pickStatement(): without this the URI can point
    // into a provider (Photos, iCloud) the upload cannot read, or one that's revoked the moment
    // the picker closes.
    copyToCacheDirectory: true,
    multiple: false,
  }));

  if (result.canceled || !result.assets?.length) return null;

  const asset = result.assets[0];
  if (asset.size != null && asset.size > MAX_SCREENSHOT_BYTES) {
    throw new ScreenshotTooLargeError('Screenshots are limited to 8 MB.');
  }

  return {
    uri: asset.uri,
    name: asset.name ?? 'screenshot',
    type: asset.mimeType ?? 'application/octet-stream',
  };
}
