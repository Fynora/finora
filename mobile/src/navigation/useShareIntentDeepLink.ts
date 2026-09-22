import { useCallback, useEffect, useRef } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useShareIntentContext, type ShareIntentFile } from 'expo-share-intent';
import type { NavigationContainerRefWithCurrent } from '@react-navigation/native';
import { detectStatementFormat, type StatementFormat } from '../lib/statementFile';
import type { RootParamList, SharedStatementFile, SharedStatementError } from './types';

const UNSUPPORTED_MESSAGE = 'Choose a .csv or .pdf bank or credit card statement.';

/**
 * Same `finora_` key convention as useNavigationStatePersistence's own NAV_STATE_KEY and
 * appLock.ts's ENABLED_KEY. Written the instant a share is stashed (not just on successful
 * navigation) and cleared once consumed, so a process death between "share arrived" and "Import
 * tab actually processed it" -- the app killed by the OS while still bootstrapping, for instance --
 * can recover on the next cold start instead of silently losing the share. Only ever holds a
 * SharedStatementFile, never a SharedStatementError: there is nothing worth recovering from an
 * error message once the process that would have shown it is gone.
 */
const PENDING_SHARE_KEY = 'finora_pending_shared_statement';

/**
 * Same cutoff fileCacheSweep.ts uses for its own cache-file eviction. Past this age, the cache
 * file this persisted entry's `file.uri` points at has likely already been swept, so recovering
 * and attempting to upload it would just fail with a confusing "file not found" -- dropping it
 * silently here, the same way an ordinary dismissed share is silently dropped, is the more honest
 * failure mode.
 */
const PENDING_SHARE_MAX_AGE_MS = 60 * 60 * 1000;

type Pending = { kind: 'file'; value: SharedStatementFile } | { kind: 'error'; value: SharedStatementError };

/**
 * expo-share-intent's own published type says `fileName: string`, but ExpoShareIntentModule.kt's
 * getFileInfo reads Android's OpenableColumns.DISPLAY_NAME from a ContentResolver cursor that can
 * come back null for some content providers -- utils.ts's parseShareIntent then passes that
 * through as `file.fileName || null`, looser than the declared type. Falls back name -> mimeType ->
 * resolved path before giving up, rather than trusting the name alone the way pickStatement() can
 * (a real DocumentPicker asset always has one).
 */
function detectFormatFromShareIntentFile(file: ShareIntentFile): StatementFormat | null {
  const byName = file.fileName ? detectStatementFormat(file.fileName) : null;
  if (byName) return byName;
  if (file.mimeType === 'application/pdf') return 'PDF';
  if (file.mimeType === 'text/csv' || file.mimeType === 'text/comma-separated-values') return 'CSV';
  return file.path ? detectStatementFormat(file.path) : null;
}

function toPending(file: ShareIntentFile): Pending {
  const nonce = Date.now();
  const format = detectFormatFromShareIntentFile(file);
  if (!format) {
    return { kind: 'error', value: { message: UNSUPPORTED_MESSAGE, nonce } };
  }
  // A synthesized name when the provider gave none -- upload() and the review screen both display
  // this name, so it must never be empty, even though it does not need to be the real one.
  const name = file.fileName || `statement.${format === 'PDF' ? 'pdf' : 'csv'}`;
  return {
    kind: 'file',
    value: {
      file: { uri: file.path, name, type: format === 'PDF' ? 'application/pdf' : 'text/csv' },
      format,
      nonce,
    },
  };
}

/**
 * Android's share sheet arrival path: sharing a PDF/CSV straight out of another app (e.g. a bank
 * app's own Share action) rather than picking one from "Choose a file". expo-share-intent's own
 * useShareIntentContext() already collapses cold-start (app launched by the share) and warm-start
 * (app already running, resumed by the share) into one reactive `shareIntent` value -- unlike
 * useAppPathDeepLink's own https-link handling, no separate Linking.getInitialURL()/
 * addEventListener wiring is needed here.
 *
 * Same stash-until-ready shape as usePushNotificationNavigation: the share can arrive before the
 * Import tab exists to navigate to (signed out, mid phone verification), so it waits in a ref and
 * replays once `ready` turns true; a real sign-out drops anything still waiting, for the same
 * reason a tapped push is dropped rather than replayed for whoever signs in next -- the Import tab
 * has no per-user scoping of its own to reject a stale arrival the way an emailed link's token does.
 *
 * On Android, expo-share-intent's native module resolves a shared content:// URI into a real file
 * copied into the app's own cache directory before `file.path` is ever populated here (confirmed by
 * reading expo-share-intent 8.0.1's ExpoShareIntentModule.kt getDataColumn, not assumed) -- the same
 * "a provider URI can be unreadable or revoked later" reasoning pickStatement()'s own
 * copyToCacheDirectory comment documents, already handled on the library's side.
 *
 * A stashed-but-not-yet-consumed share also survives a killed process (not just a backgrounded
 * one): the instant it's stashed into `pendingRef`, it's mirrored into AsyncStorage under
 * PENDING_SHARE_KEY, and a mount-time effect recovers it on the next cold start if nothing arrived
 * live this session. This does NOT cover two shares arriving in quick succession before either is
 * consumed -- both the in-memory `pendingRef` and the persisted key are a single slot, so the
 * second still overwrites the first either way. That is a known, accepted limitation (see the
 * implementation plan's own "Corrections from review" section), not something this persistence
 * layer changes.
 */
export function useShareIntentDeepLink(
  navigationRef: NavigationContainerRefWithCurrent<RootParamList>,
  ready: boolean,
  signedIn: boolean,
) {
  const { hasShareIntent, shareIntent } = useShareIntentContext();
  const pendingRef = useRef<Pending | null>(null);
  const readyRef = useRef(ready);
  const wasSignedInRef = useRef(signedIn);

  const tryConsume = useCallback(() => {
    if (!readyRef.current) return;
    if (!navigationRef.current || !navigationRef.isReady()) return;
    const pending = pendingRef.current;
    if (!pending) return;
    pendingRef.current = null;
    if (pending.kind === 'file') {
      void AsyncStorage.removeItem(PENDING_SHARE_KEY);
      navigationRef.navigate('Import', { sharedFile: pending.value });
    } else {
      navigationRef.navigate('Import', { sharedFileError: pending.value });
    }
  }, [navigationRef]);

  // This effect only ever builds a plain object and stores it in a ref -- unlike ImportScreen's own
  // consumption of the result, which can trigger upload(), a real network call, so THAT step
  // deliberately runs in ImportScreen's own effect, not here. Safe to run more than once for the
  // same shareIntent value (e.g. React StrictMode's dev-mode double-invoke) -- re-persisting the
  // same value to AsyncStorage is equally harmless.
  //
  // Deliberately does NOT call expo-share-intent's own resetShareIntent(): the library's own
  // resetOnBackground default (true) already clears its state on backgrounding, and this effect's
  // own [hasShareIntent, shareIntent] dependency array already prevents reprocessing an unchanged
  // value -- shareIntent is a fresh object identity per native event, so nothing here re-runs
  // without a genuinely new share. Calling it eagerly bought nothing and only added a window where
  // the native/JS state could be cleared before pendingRef was actually set.
  useEffect(() => {
    if (!hasShareIntent) return;
    const file = shareIntent.files?.[0];
    if (!file) return;
    const pending = toPending(file);
    pendingRef.current = pending;
    if (pending.kind === 'file') {
      void AsyncStorage.setItem(PENDING_SHARE_KEY, JSON.stringify(pending.value));
    }
    tryConsume();
  }, [hasShareIntent, shareIntent, tryConsume]);

  // Mount-only recovery for a process killed after a share was stashed but before it was consumed.
  // Only hydrates when nothing has claimed pendingRef yet -- a share arriving live this session
  // (the effect above) is always more current than a persisted one and must win if both somehow
  // race. A `cancelled` guard, same shape as useAppPathDeepLink's own getInitialURL effect, so a
  // torn-down mount can't act on a navigationRef that no longer belongs to it.
  useEffect(() => {
    let cancelled = false;
    void AsyncStorage.getItem(PENDING_SHARE_KEY).then((raw) => {
      if (cancelled || !raw || pendingRef.current) return;
      let value: SharedStatementFile;
      try {
        value = JSON.parse(raw) as SharedStatementFile;
      } catch {
        void AsyncStorage.removeItem(PENDING_SHARE_KEY);
        return;
      }
      if (Date.now() - value.nonce > PENDING_SHARE_MAX_AGE_MS) {
        void AsyncStorage.removeItem(PENDING_SHARE_KEY);
        return;
      }
      pendingRef.current = { kind: 'file', value };
      tryConsume();
    });
    return () => { cancelled = true; };
    // Deliberately [] -- a one-time recovery check for this mount's cold start, not something that
    // should re-run as `tryConsume`'s identity changes with navigationRef/ready.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    readyRef.current = ready;
    if (wasSignedInRef.current && !signedIn) {
      pendingRef.current = null;
      void AsyncStorage.removeItem(PENDING_SHARE_KEY);
    }
    wasSignedInRef.current = signedIn;
    tryConsume();
  }, [ready, signedIn, tryConsume]);

  return { onNavigationReady: tryConsume };
}
