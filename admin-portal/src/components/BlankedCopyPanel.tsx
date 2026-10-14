import { useState } from 'react';
import { FileText } from 'lucide-react';
import { downloadBlob } from '../lib/download';
import type { BlankedCopy, BlankedCopyDifference } from '../types';

/** One label per `BlankedCopyComparison.Field` on the backend. */
const FIELD_LABEL: Record<string, string> = {
  SECTION_COUNT: 'Number of accounts', ROW_COUNT: 'Number of rows', UNPARSEABLE_COUNT: 'Rows that could not be read',
  DATE: 'date', AMOUNT: 'amount', DIRECTION: 'money in or out', BALANCE: 'balance', DESCRIPTION: 'description',
  PERIOD_START: 'Statement start date', PERIOD_END: 'Statement end date',
  OPENING_BALANCE: 'Opening balance', CLOSING_BALANCE: 'Closing balance', FINDINGS: 'Check results',
};

function line(d: BlankedCopyDifference): string {
  const label = FIELD_LABEL[d.field] ?? d.field;
  // A statement can hold several accounts, each with its own rows; without this, row 4 of the
  // second account would read the same as row 4 of the first. The first account is left unnamed,
  // as almost every statement has only the one.
  const account = d.section > 0 ? `Account ${d.section + 1}, ` : '';
  if (d.row === null) return `${account}${label}`;
  const hidden = d.maskedTokens.length ? ` (hidden words: ${d.maskedTokens.join(' ')})` : '';
  return `${account}Row ${d.row + 1}: ${label}${hidden}`;
}

/**
 * Downloads a held statement's blanked copy and shows whether it reads the same as the real one.
 * Only field names, row numbers and words already hidden in the copy are shown -- never a value.
 */
export default function BlankedCopyPanel({ fetchCopy }: { fetchCopy: () => Promise<BlankedCopy> }) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<BlankedCopy | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    // A second attempt that fails must not leave the first attempt's verdict on screen beside it.
    setResult(null);
    let copy: BlankedCopy;
    try {
      copy = await fetchCopy();
    } catch (err) {
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setError(message ?? 'The blanked copy could not be made.');
      setBusy(false);
      return;
    }
    // Past this point the copy exists and its making was audited server-side; only the browser's
    // save can still fail, and the check's verdict on the copy stands either way.
    setResult(copy);
    try {
      downloadBlob(new Blob([copy.trace], { type: 'text/plain' }), copy.fileName);
    } catch {
      setError('The blanked copy was made, but your browser could not save it. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-2">
      <button
        type="button"
        onClick={() => void run()}
        disabled={busy}
        className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-1.5 text-xs text-ink hover:bg-card disabled:opacity-50"
      >
        <FileText className="h-3.5 w-3.5" />
        {busy ? 'Making blanked copy…' : 'Download blanked copy'}
      </button>
      {error && <p role="alert" className="text-xs text-danger">{error}</p>}
      {result?.check === 'MATCHES' && <p className="text-xs text-ink">Matches the real statement</p>}
      {result?.check === 'NOT_CHECKED' && (
        <p className="text-xs text-ink">
          {result.notCheckedReason ? `Not checked: ${result.notCheckedReason}` : 'Not checked'}
        </p>
      )}
      {result?.check === 'DIFFERS' && (
        <div className="text-xs text-ink">
          <p>Does not match the real statement</p>
          <ul aria-label="Differences" className="list-disc pl-5">
            {result.differences.map((d, i) => <li key={i}>{line(d)}</li>)}
          </ul>
        </div>
      )}
    </div>
  );
}
