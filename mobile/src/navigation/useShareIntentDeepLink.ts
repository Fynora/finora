import { useCallback, useEffect, useRef } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useShareIntentContext, type ShareIntentFile } from 'expo-share-intent';
import type { NavigationContainerRefWithCurrent } from '@react-navigation/native';
import { detectStatementFormat, type StatementFormat } from '../lib/statementFile';
import { safeStorage } from '../lib/safeStorage';
import type { RootParamList, SharedStatementFile, SharedStatementError } from './types';

const UNSUPPORTED_MESSAGE = 'Choose a .csv or .pdf bank or credit card statement.';

/**
 * Same `finora_` key convention as useNavigationStatePersistence's own NAV_STATE_KEY and
 * appLock.ts's ENABLED_KEY. Written the instant a share is stashed (not just on successful
 * navigation) and cleared once consumed, so a process death between "share arrived" and "Import
 * tab actually processed it" -- the app killed by the OS while still bootstrapping, for instance --
 * can recover on the next cold start instead of silently losing the share.
 */
const PENDING_SHARE_KEY = 'finora_pending_shared_statement';

/**
 * AuthContext.tsx's own USER_ID_KEY, hardcoded here rather than imported -- it is a private
 * module const there, not part of AuthContext's exported surface, and this hook already follows
 * the same "reference another module's constant by value, with a comment" convention its own test
 * file uses for useNavigationStatePersistence's NAV_STATE_KEY. AuthContext writes/removes this key
 * in lockstep with `token` itself (set together at login/register/bootstrap-restore, removed
 * together at logout), so reading it independently here stays consistent with `signedIn`.
 *
 * Exists to close a real gap: without it, a share stashed while User A is signed in, persisted to
 * AsyncStorage, and never consumed before the process is killed, would be recovered and handed to
 * WHOEVER completes the next successful sign-in on this device -- including a different account --
 * because the existing sign-out-clears-it guarantee (the `wasSignedInRef` transition check below)
 * only ever fires for an in-process true -> false transition, and can never retroactively catch a
 * sign-out (or account switch) that happened entirely in a PREVIOUS process lifetime, which is
 * exactly the boundary this persistence layer exists to survive. Tagging each stash with the
 * account it belonged to, and refusing to consume a mismatch, closes that.
 */
const CURRENT_USER_ID_KEY = 'finora_user_id';

/**
 * Same cutoff fileCacheSweep.ts uses for its own cache-file eviction. Past this age, the cache
 * file this persisted entry's `file.uri` points at has likely already been swept, so recovering
 * and attempting to upload it would just fail with a confusing "file not found" -- dropping it
 * silently here, the same way an ordinary dismissed share is silently dropped, is the more honest
 * failure mode.
 */
const PENDING_SHARE_MAX_AGE_MS = 60 * 60 * 1000;

/**
 * `userId` is `null` for a share that arrived while nobody was signed in on this device -- that
 * is a legitimate, already-supported case (the whole point of stashing until `ready`), and stays
 * consumable by whoever signs in next, same as today. A NON-null `userId` must match the
 * currently signed-in account exactly, or the entry is dropped rather than consumed. See
 * CURRENT_USER_ID_KEY's own doc comment for why this exists.
 */
type StoredShare = { value: SharedStatementFile; userId: string | null };
type Pending = { kind: 'file'; stored: StoredShare } | { kind: 'error'; value: SharedStatementError };

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

function toPending(file: ShareIntentFile, currentUserId: string | null): Pending {
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
    stored: {
      value: {
        file: { uri: file.path, name, type: format === 'PDF' ? 'application/pdf' : 'text/csv' },
        format,
        nonce,
      },
      userId: currentUserId,
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
 * replays once `ready` turns true; a real in-process sign-out drops anything still waiting, for
 * the same reason a tapped push is dropped rather than replayed for whoever signs in next. The
 * per-account `userId` tag below extends that same guarantee across a process death, which the
 * in-process check alone cannot cover -- see CURRENT_USER_ID_KEY's own doc comment.
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
  // Best-effort, not authoritative: refreshed whenever `signedIn` changes (see the effect below),
  // so it can lag the true current value by the time of an async SecureStore read resolving. A
  // share that arrives in that narrow window gets tagged `userId: null` (the same, already-safe
  // "arrived while signed out" treatment) rather than correctly scoped -- strictly more permissive
  // than the bug this exists to close, never less safe, and unavoidable given SecureStore has no
  // synchronous read.
  const currentUserIdRef = useRef<string | null>(null);
  // Separate from currentUserIdRef itself: `null` is a real, meaningful value (nobody signed in),
  // so it cannot double as "haven't read it yet". tryConsume needs to tell those two apart -- see
  // its own comment on why guessing in EITHER direction while this is false would be wrong, not
  // just imprecise: AsyncStorage.getItem(PENDING_SHARE_KEY) below and this identity read are two
  // independent async calls with no ordering guarantee between them, so the recovery read can and
  // does sometimes resolve first in practice (caught by this hook's own test suite).
  const identityLoadedRef = useRef(false);

  const tryConsume = useCallback(() => {
    if (!readyRef.current) return;
    if (!navigationRef.current || !navigationRef.isReady()) return;
    const pending = pendingRef.current;
    if (!pending) return;
    if (pending.kind === 'file' && pending.stored.userId !== null && !identityLoadedRef.current) {
      // A tagged (non-null) entry, but this hook's own read of the CURRENT account hasn't
      // resolved yet -- cannot yet tell whether this is a match or a leak, so wait rather than
      // guess either way. Retried once the identity-read effect below resolves.
      return;
    }
    pendingRef.current = null;
    if (pending.kind === 'error') {
      navigationRef.navigate('Import', { sharedFileError: pending.value });
      return;
    }
    void AsyncStorage.removeItem(PENDING_SHARE_KEY);
    const { userId } = pending.stored;
    if (userId !== null && userId !== currentUserIdRef.current) {
      // Belongs to a different account than the one currently signed in -- see
      // CURRENT_USER_ID_KEY's own doc comment. Dropped silently, the same way an unsupported
      // shared file's rejection is silent: this is not a failure the current user caused or needs
      // to be told about.
      return;
    }
    navigationRef.navigate('Import', { sharedFile: pending.stored.value });
  }, [navigationRef]);

  // Refreshed whenever `signedIn` changes, and retries tryConsume() once it resolves -- that
  // retry is what actually unblocks a legitimate same-account recovery that arrived at
  // tryConsume() before this had a chance to load (see identityLoadedRef's own comment).
  useEffect(() => {
    let cancelled = false;
    void safeStorage.getItem(CURRENT_USER_ID_KEY).then((id) => {
      if (cancelled) return;
      currentUserIdRef.current = id;
      identityLoadedRef.current = true;
      tryConsume();
    });
    return () => { cancelled = true; };
  }, [signedIn, tryConsume]);

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
    const pending = toPending(file, currentUserIdRef.current);
    pendingRef.current = pending;
    if (pending.kind === 'file') {
      void AsyncStorage.setItem(PENDING_SHARE_KEY, JSON.stringify(pending.stored));
    }
    tryConsume();
  }, [hasShareIntent, shareIntent, tryConsume]);

  // Mount-only recovery for a process killed after a share was stashed but before it was consumed.
  // Only hydrates when nothing has claimed pendingRef yet -- a share arriving live this session
  // (the effect above) is always more current than a persisted one and must win if both somehow
  // race. A `cancelled` guard, same shape as useAppPathDeepLink's own getInitialURL effect, so a
  // torn-down mount can't act on a navigationRef that no longer belongs to it. The account check
  // itself happens later, in tryConsume -- this effect's job is only to hydrate pendingRef with
  // whatever was persisted, tag and all.
  useEffect(() => {
    let cancelled = false;
    void AsyncStorage.getItem(PENDING_SHARE_KEY).then((raw) => {
      if (cancelled || !raw || pendingRef.current) return;
      let stored: StoredShare;
      try {
        stored = JSON.parse(raw) as StoredShare;
      } catch {
        void AsyncStorage.removeItem(PENDING_SHARE_KEY);
        return;
      }
      if (Date.now() - stored.value.nonce > PENDING_SHARE_MAX_AGE_MS) {
        void AsyncStorage.removeItem(PENDING_SHARE_KEY);
        return;
      }
      pendingRef.current = { kind: 'file', stored };
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
