import AsyncStorage from '@react-native-async-storage/async-storage';
import { File, Paths } from 'expo-file-system';

/**
 * iOS-only counterpart to fileCacheSweep.ts. On iOS the Share Extension (expo-share-intent) cannot
 * write into this app's own cache directory, so it copies a shared statement into the App Group
 * container instead, under a UUID name. Nothing else ever deletes those copies: the library only
 * clears the UserDefaults pointer to them (clearShareIntent), and fileCacheSweep sweeps
 * Paths.cache alone. Measured on the Simulator, statements from earlier test shares were still
 * sitting there ten hours later. A bank statement should not outlive the import it was shared for,
 * let alone a sign-out.
 *
 * Deliberately NOT age-by-modification-time like fileCacheSweep: the extension copies with
 * FileManager.copyItem, which preserves the SOURCE file's modification time (verified: a copy of a
 * 30-day-old file reads back as 30 days old). A freshly shared statement that was downloaded
 * yesterday would therefore look stale, and this runs at launch -- the exact moment a share that
 * cold-started the app is still waiting to be read -- so an mtime sweep would delete it before the
 * upload. Instead this keeps its own record of when it FIRST saw each file (the app's own clock),
 * never deletes on the first sighting, and deletes once a file has been known for an hour.
 *
 * Restricted to *.pdf / *.csv, never a dotfile: the container's root also holds
 * .com.apple.mobile_container_manager.metadata.plist, which belongs to the OS.
 *
 * Every path here is best-effort and never throws; a failure just leaves the file for a later run.
 */
const FIRST_SEEN_KEY = 'finora_shared_container_first_seen';
const MAX_AGE_MS = 60 * 60 * 1000;
const STATEMENT_NAME = /^(?!\.).+\.(pdf|csv)$/i;

/** Empty on Android and wherever the app has no App Group, which makes both entry points no-ops. */
function sharedStatementFiles(): File[] {
  const files: File[] = [];
  for (const dir of Object.values(Paths.appleSharedContainers)) {
    try {
      for (const entry of dir.list()) {
        if (entry instanceof File && STATEMENT_NAME.test(entry.name)) files.push(entry);
      }
    } catch {
      // An unreadable container is not fatal, and there is nothing to sweep in it.
    }
  }
  return files;
}

async function readFirstSeen(): Promise<Record<string, number>> {
  try {
    const raw = await AsyncStorage.getItem(FIRST_SEEN_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : {};
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const seen: Record<string, number> = {};
    for (const [name, at] of Object.entries(parsed)) {
      if (typeof at === 'number' && Number.isFinite(at)) seen[name] = at;
    }
    return seen;
  } catch {
    return {};
  }
}

/** `now` is injectable purely so tests can move the clock; production calls it with no argument. */
export async function sweepSharedContainers(now: number = Date.now()): Promise<void> {
  try {
    const files = sharedStatementFiles();
    const seen = await readFirstSeen();
    const kept: Record<string, number> = {};
    for (const file of files) {
      const firstSeenAt = seen[file.name];
      if (firstSeenAt === undefined) {
        kept[file.name] = now;
        continue;
      }
      if (now - firstSeenAt > MAX_AGE_MS) {
        try {
          file.delete();
          continue;
        } catch {
          // Could not delete this run; keep tracking it so the next sweep tries again.
        }
      }
      kept[file.name] = firstSeenAt;
    }
    await AsyncStorage.setItem(FIRST_SEEN_KEY, JSON.stringify(kept));
  } catch {
    // Best-effort, see the module comment.
  }
}

/**
 * Sign-out: delete every shared statement now, with no age margin. The share hook drops a pending
 * share at the same signed-in -> signed-out transition, so nothing can still be waiting on these,
 * and it is the one moment the app can say for certain the previous account's import is over.
 */
export async function purgeSharedContainers(): Promise<void> {
  try {
    for (const file of sharedStatementFiles()) {
      try {
        file.delete();
      } catch {
        // One undeletable file must not stop the rest.
      }
    }
    await AsyncStorage.removeItem(FIRST_SEEN_KEY);
  } catch {
    // Best-effort, see the module comment.
  }
}
