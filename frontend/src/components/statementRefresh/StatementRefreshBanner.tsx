import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { RefreshCw, X } from 'lucide-react';
import {
  statementRefreshApi, type RefreshPendingStatement, type RefreshRunDetail,
} from '../../api/endpoints';
import { Button, useDialogA11y } from '../../design-system';
import { PasswordInput } from '../PasswordInput';
import { useInvalidateMoneyFigures } from '../../lib/invalidateMoneyFigures';
import { RefreshSummaryDialog } from './RefreshSummaryDialog';
import { period } from './refreshFormat';

export const REFRESH_OVERVIEW_KEY = ['statement-refresh-overview'];

/**
 * Statement refresh, step 5: the in-app banner. Shown only when the latest check found statements
 * an improved parser would read differently. One tap updates them all -- applied straight away,
 * with no review step, and followed by a "what changed" summary. A protected PDF whose password
 * was not saved is listed separately and asks for it.
 *
 * Renders nothing while refreshing is switched off, and nothing when there is nothing to offer.
 */
export function StatementRefreshBanner() {
  const queryClient = useQueryClient();
  const invalidateMoney = useInvalidateMoneyFigures();
  const { data } = useQuery({ queryKey: REFRESH_OVERVIEW_KEY, queryFn: statementRefreshApi.overview, retry: false });
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [results, setResults] = useState<RefreshRunDetail[] | null>(null);
  const [asking, setAsking] = useState<RefreshPendingStatement | null>(null);

  if (!data?.enabled) return null;
  const updatable = data.updatable;
  const locked = data.needsPassword;
  if (updatable.length === 0 && locked.length === 0 && !results) return null;

  function afterChanges() {
    void queryClient.invalidateQueries({ queryKey: REFRESH_OVERVIEW_KEY });
    void queryClient.invalidateQueries({ queryKey: ['statement-imports'] });
    void queryClient.invalidateQueries({ queryKey: ['accounts'] });
    invalidateMoney();
  }

  async function updateAll() {
    setBusy(true);
    setFailed(false);
    const all: RefreshRunDetail[] = [];
    try {
      // Ten at a time server-side; keep going while more remain and the last call made progress.
      for (let round = 0; round < 20; round++) {
        const res = await statementRefreshApi.applyAll();
        all.push(...res.results);
        if (res.remaining === 0 || res.results.length === 0) break;
      }
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
      if (all.length > 0) {
        setResults(all);
        afterChanges();
      }
    }
  }

  return (
    <>
      {(updatable.length > 0 || locked.length > 0) && (
        <div
          className="bg-card border border-primary/40 rounded-xl2 shadow-card p-4 space-y-3"
          data-testid="statement-refresh-banner"
          role="region"
          aria-label="Statement updates"
        >
          <div className="flex items-start gap-3">
            <RefreshCw size={18} className="text-primary shrink-0 mt-0.5" aria-hidden="true" />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium text-ink">
                {updatable.length > 0
                  ? `We now read ${updatable.length} of your ${updatable.length === 1 ? 'statement' : 'statements'} more accurately`
                  : 'Some of your statements need their password to be checked'}
              </p>
              <p className="text-xs text-muted mt-0.5">
                {updatable.length > 0
                  ? "Updating corrects them from the files you already uploaded — nothing is re-uploaded, and your own edits, categories and notes are kept. You'll see exactly what changed."
                  : "We improved how statements are read, but these are password protected, so we couldn't check them."}
              </p>
            </div>
          </div>

          {updatable.length > 0 && (
            <div className="flex items-center gap-3 flex-wrap">
              <Button size="sm" onClick={() => void updateAll()} disabled={busy} loading={busy}>
                {busy ? 'Updating…' : `Update ${updatable.length} ${updatable.length === 1 ? 'statement' : 'statements'}`}
              </Button>
              {failed && (
                <p className="text-xs text-danger" role="alert">Something went wrong while updating — please try again.</p>
              )}
            </div>
          )}

          {locked.length > 0 && (
            <div className="border-t border-border pt-3">
              <p className="text-xs text-muted mb-2">
                {locked.length === 1 ? 'This statement is' : 'These statements are'} password protected:
              </p>
              <ul className="space-y-2">
                {locked.map((s) => (
                  <li key={s.statementImportId} className="flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-sm text-ink break-all">{s.fileName}</p>
                      <p className="text-2xs text-muted">{[s.accountName, period(s.periodStart, s.periodEnd)].filter(Boolean).join(' · ')}</p>
                    </div>
                    <Button variant="secondary" size="sm" onClick={() => setAsking(s)} disabled={busy}
                      aria-label={`Enter the password for ${s.fileName}`}>
                      Enter password
                    </Button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {asking && (
        <RefreshPasswordDialog
          statement={asking}
          savePasswordAvailable={data.savePasswordAvailable}
          onClose={() => setAsking(null)}
          onDone={(detail) => {
            setAsking(null);
            setResults([detail]);
            afterChanges();
          }}
        />
      )}

      {results && <RefreshSummaryDialog results={results} onClose={() => setResults(null)} />}
    </>
  );
}

/**
 * The password for one protected statement, to update it now -- and, only if the user ticks the
 * box (never ticked for them), to keep it so future updates need no password.
 */
function RefreshPasswordDialog({
  statement, savePasswordAvailable, onClose, onDone,
}: {
  statement: RefreshPendingStatement;
  savePasswordAvailable: boolean;
  onClose: () => void;
  onDone: (detail: RefreshRunDetail) => void;
}) {
  const [password, setPassword] = useState('');
  const [keep, setKeep] = useState(false);
  const [busy, setBusy] = useState(false);
  const [wrong, setWrong] = useState(false);
  const [failed, setFailed] = useState(false);
  const panelRef = useDialogA11y<HTMLFormElement>({ onClose, closeDisabled: busy });

  async function submit() {
    setBusy(true);
    setWrong(false);
    setFailed(false);
    try {
      const outcome = await statementRefreshApi.refreshOne(statement.statementImportId, password,
        savePasswordAvailable && keep);
      if (outcome.status === 'NEEDS_PASSWORD') {
        setWrong(true);
        return;
      }
      onDone(outcome.runId
        ? await statementRefreshApi.run(outcome.runId)
        : { ...emptyDetail(statement), status: outcome.status, reason: outcome.reason });
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={busy ? undefined : onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <form
          ref={panelRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="refresh-password-title"
          tabIndex={-1}
          data-testid="refresh-password-dialog"
          className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5 pointer-events-auto space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (password && !busy) void submit();
          }}
        >
          <div className="flex items-start justify-between gap-3">
            <h3 id="refresh-password-title" className="font-semibold text-ink text-sm">Update this statement</h3>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink shrink-0" disabled={busy}>
              <X size={18} />
            </button>
          </div>
          <p className="text-xs text-muted">
            <span className="text-ink font-medium break-all">{statement.fileName}</span> is password protected.
            Enter the password your bank uses for it.
          </p>
          <div>
            <label htmlFor="refresh-password" className="block text-sm font-medium text-ink mb-1">Statement password</label>
            <PasswordInput
              id="refresh-password"
              autoComplete="off"
              autoFocus
              className="w-full border border-border rounded px-3 py-2 pr-10 text-sm"
              value={password}
              onChange={setPassword}
              disabled={busy}
              aria-describedby="refresh-password-help"
            />
            <p id="refresh-password-help" className={`text-xs mt-1 ${wrong || failed ? 'text-danger' : 'text-muted'}`}
              role={wrong || failed ? 'alert' : undefined}>
              {wrong
                ? "That password didn't open this statement — check it and try again."
                : failed ? 'Something went wrong — please try again.' : 'The password your bank uses for this statement.'}
            </p>
          </div>
          {savePasswordAvailable && (
            <div className="flex items-start gap-2">
              <input id="refresh-keep-password" type="checkbox" className="mt-0.5" checked={keep}
                onChange={(e) => setKeep(e.target.checked)} disabled={busy} aria-describedby="refresh-keep-password-help" />
              <div>
                <label htmlFor="refresh-keep-password" className="text-sm text-ink">
                  Keep this password so future updates don't need it
                </label>
                <p id="refresh-keep-password-help" className="text-xs text-muted">
                  Stored encrypted, only for this statement. Remove it any time in Settings → Data.
                </p>
              </div>
            </div>
          )}
          <Button type="submit" className="w-full" disabled={!password || busy} loading={busy}>
            {busy ? 'Updating…' : 'Update statement'}
          </Button>
        </form>
      </div>
    </>
  );
}

function emptyDetail(s: RefreshPendingStatement): RefreshRunDetail {
  return {
    runId: null, statementImportId: s.statementImportId, fileName: s.fileName, accountName: s.accountName,
    periodStart: s.periodStart, periodEnd: s.periodEnd, status: 'NO_CHANGES', createdAt: null,
    rowsChanged: 0, rowsAdded: 0, rowsRemoved: 0, factsChanged: 0, balanceChange: null, reason: null,
    changed: [], added: [], removed: [], skippedAsDuplicate: [], facts: [],
  };
}
