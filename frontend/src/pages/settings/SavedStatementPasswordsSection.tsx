import { useEffect, useState } from 'react';
import { statementPasswordsApi, type SavedStatementPassword } from '../../api/endpoints';
import { Button } from '../../design-system/Button';
import { ConfirmDialog } from '../../design-system/ConfirmDialog';
import { formatDateDDMMMYYYY } from '../../utils/date';

/** An instant as the viewer's own calendar day (YYYY-MM-DD) -- slicing the UTC string would show
 *  the previous day for anything saved before 05:30 IST. */
function localDay(instant: string): string {
  const d = new Date(instant);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * Settings -> Data -> Saved statement passwords (statement refresh, step 4). Lists the statements
 * the user let Fynora keep a password for -- never the password itself -- and removes one or all.
 * Hidden entirely while saving is switched off and nothing was ever saved.
 */
export function SavedStatementPasswordsSection() {
  const [items, setItems] = useState<SavedStatementPassword[] | null>(null);
  const [saveAvailable, setSaveAvailable] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [confirmAll, setConfirmAll] = useState(false);
  const [removingAll, setRemovingAll] = useState(false);
  const [actionFailed, setActionFailed] = useState(false);

  useEffect(() => {
    statementPasswordsApi.list()
      .then((r) => { setItems(r.items); setSaveAvailable(r.saveAvailable); })
      .catch(() => setLoadFailed(true));
  }, []);

  if (!loadFailed && (items === null || (!saveAvailable && items.length === 0))) return null;

  async function remove(id: string) {
    setBusyId(id);
    setActionFailed(false);
    try {
      await statementPasswordsApi.remove(id);
      setItems((current) => (current ?? []).filter((i) => i.statementImportId !== id));
    } catch {
      setActionFailed(true);
    } finally {
      setBusyId(null);
    }
  }

  async function removeAll() {
    setRemovingAll(true);
    setActionFailed(false);
    try {
      await statementPasswordsApi.removeAll();
      setItems([]);
      setConfirmAll(false);
    } catch {
      setActionFailed(true);
      setConfirmAll(false);
    } finally {
      setRemovingAll(false);
    }
  }

  const period = (i: SavedStatementPassword) =>
    i.periodStart && i.periodEnd
      ? `${formatDateDDMMMYYYY(i.periodStart)} – ${formatDateDDMMMYYYY(i.periodEnd)}`
      : null;

  return (
    <div className="pt-4 mt-4 border-t border-border" data-testid="saved-statement-passwords">
      <p className="text-ink font-medium text-sm">Saved statement passwords</p>
      <p className="text-muted text-2xs mt-1 mb-3">
        Passwords you chose to keep so Fynora can read these statements again. They're stored encrypted
        and never shown. Removing one means we'll ask for it the next time the statement is read.
      </p>
      {loadFailed && (
        <p className="text-xs text-warning">Couldn't load your saved passwords just now.</p>
      )}
      {items && items.length === 0 && (
        <p className="text-xs text-muted">You haven't saved any statement passwords.</p>
      )}
      {items && items.length > 0 && (
        <>
          <ul className="divide-y divide-border border border-border rounded">
            {items.map((i) => (
              <li key={i.statementImportId} className="flex items-center justify-between gap-3 px-3 py-2">
                <div className="min-w-0">
                  <p className="text-sm text-ink break-all">{i.fileName}</p>
                  <p className="text-2xs text-muted">
                    {[i.accountName, period(i), `saved ${formatDateDDMMMYYYY(localDay(i.savedAt))}`]
                      .filter(Boolean).join(' · ')}
                  </p>
                </div>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={busyId !== null || removingAll}
                  onClick={() => void remove(i.statementImportId)}
                  aria-label={`Remove saved password for ${i.fileName}`}
                >
                  Remove
                </Button>
              </li>
            ))}
          </ul>
          <Button
            variant="secondary"
            size="sm"
            className="mt-3"
            disabled={busyId !== null || removingAll}
            onClick={() => setConfirmAll(true)}
          >
            Remove all
          </Button>
        </>
      )}
      {actionFailed && (
        <p className="text-xs text-danger mt-2" role="alert">Couldn't remove that just now — please try again.</p>
      )}
      {confirmAll && (
        <ConfirmDialog
          title="Remove all saved passwords?"
          message="Fynora will ask for each statement's password again the next time it needs to read it."
          confirmLabel="Remove all"
          danger
          busy={removingAll}
          onConfirm={() => void removeAll()}
          onCancel={() => setConfirmAll(false)}
        />
      )}
    </div>
  );
}
