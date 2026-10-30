import { useCallback, useEffect, useMemo, useState } from 'react';
import type { Account } from '../types';
import { shouldAskWhichBank, whichBankPromptKey } from './bankChoice';
import { safeStorage } from './safeStorage';

/**
 * The one-time "Which bank is this?" prompt on imported accounts whose bank is unknown (spec
 * 2026-10-22-bank-from-profile-and-required-bank-design.md, section 7). Once dismissed, or once the
 * person picks a bank in the edit sheet ("Other bank" again counts), it never shows for that
 * account again: the answer is kept on the device per account id (safeStorage).
 *
 * Nothing shows until the stored answers have been read, so a prompt already dismissed never
 * flashes up for a moment on opening the screen. A failed read behaves like "nothing stored"
 * (safeStorage's contract), which at worst asks once more.
 */
export function useWhichBankPrompt(accounts: Account[]) {
  const candidateIds = useMemo(
    () => accounts.filter(shouldAskWhichBank).map((a) => a.id).sort().join(','),
    [accounts],
  );
  // Ids whose stored answer has been read, and which of them were answered.
  const [read, setRead] = useState<Set<string>>(() => new Set());
  const [done, setDone] = useState<Set<string>>(() => new Set());

  useEffect(() => {
    const ids = candidateIds ? candidateIds.split(',') : [];
    const unread = ids.filter((id) => !read.has(id));
    if (unread.length === 0) return;
    let cancelled = false;
    void Promise.all(unread.map(async (id) => [id, await safeStorage.getItem(whichBankPromptKey(id))] as const))
      .then((results) => {
        if (cancelled) return;
        setRead((prev) => new Set([...prev, ...unread]));
        const answered = results.filter(([, value]) => value != null).map(([id]) => id);
        if (answered.length > 0) setDone((prev) => new Set([...prev, ...answered]));
      });
    return () => { cancelled = true; };
  }, [candidateIds, read]);

  const markDone = useCallback((accountId: string) => {
    setDone((prev) => new Set(prev).add(accountId));
    void safeStorage.setItem(whichBankPromptKey(accountId), '1');
  }, []);

  const shouldShow = useCallback(
    (account: Account) => shouldAskWhichBank(account) && read.has(account.id) && !done.has(account.id),
    [read, done],
  );

  return { shouldShow, markDone };
}
