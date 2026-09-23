import * as DocumentPicker from 'expo-document-picker';
import * as appLock from './appLock';
import type { RNFile } from '../api/endpoints';

/**
 * Picking a statement to import.
 *
 * The web app drops a file onto a drag-and-drop zone and hands the resulting `File` to axios. None
 * of that exists here: there is no drag target on a phone, and React Native's FormData takes a
 * plain `{uri, name, type}` descriptor rather than a `File`. `expo-document-picker` returns almost
 * exactly that descriptor, which is why endpoints.ts types its upload arguments as RNFile.
 */

export type StatementFormat = 'CSV' | 'PDF';

export interface PickedStatement {
  file: RNFile;
  format: StatementFormat;
}

/** iOS matches on UTIs, Android on MIME types, and some providers report a CSV as text/plain or
 *  even application/octet-stream. The extension check below is what actually decides. */
const ACCEPTED_MIME = ['text/csv', 'text/comma-separated-values', 'application/pdf', 'text/plain'];

/** iOS matches on UTIs, Android on MIME types, and some providers report a CSV as text/plain or
 *  even application/octet-stream -- so this decides by extension, not the reported MIME type. The
 *  backend has separate /import/csv/stage and /import/pdf/stage endpoints, so this has to be right.
 *  Also reused by useShareIntentDeepLink.ts (Android share-sheet arrivals), which has no other
 *  reliable signal when a provider reports a share's mimeType as something generic. */
export function detectStatementFormat(name: string): StatementFormat | null {
  const lower = name.toLowerCase();
  if (lower.endsWith('.pdf')) return 'PDF';
  if (lower.endsWith('.csv')) return 'CSV';
  return null;
}

/**
 * Returns null when the user dismisses the picker -- a cancel is not an error and must not surface
 * one. Throws only for a genuinely unusable selection, so callers can show that message.
 */
export async function pickStatement(): Promise<PickedStatement | null> {
  // Bug found in review (Track D/D5): the native picker backgrounds this app the same way
  // Sharing.shareAsync does, and without this suppression AppLockGate would show a spurious lock
  // prompt the instant the picker's own UI (or a provider like Drive/iCloud) returns focus here.
  const result = await appLock.withShareSuppression(() => DocumentPicker.getDocumentAsync({
    type: ACCEPTED_MIME,
    // Copies the file into the app's cache directory. Without this the URI can point into a
    // provider (Drive, iCloud) that the upload cannot read, or that is revoked the moment the
    // picker closes -- which fails later, during upload, where the cause is far less obvious.
    copyToCacheDirectory: true,
    multiple: false,
  }));

  if (result.canceled || !result.assets?.length) return null;

  const asset = result.assets[0];
  const name = asset.name ?? 'statement';
  const format = detectStatementFormat(name);

  if (!format) {
    throw new Error('Choose a .csv or .pdf bank or credit card statement.');
  }

  return {
    file: {
      uri: asset.uri,
      name,
      type: format === 'PDF' ? 'application/pdf' : 'text/csv',
    },
    format,
  };
}
