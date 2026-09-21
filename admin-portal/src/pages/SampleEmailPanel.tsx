import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { FileUp, ShieldAlert, ShieldCheck, TriangleAlert } from 'lucide-react';
import { adminMerchantTemplatesApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import { EMAIL_TOO_LARGE, MAX_EMAIL_CHARS, readFileText } from '../lib/readFileText';
import type { SampleAnalysis } from '../types';

/** What choosing a value off the email fills into the template form. */
export type SampleFill = {
  merchantDomain?: string;
  merchantName?: string;
  receiptMarker?: string;
  amountPattern?: string;
  datePattern?: string;
};

/** The email as the test screen needs it: the HTML to run through the sanitizer, and the day it
 *  arrived for a template dated by arrival. */
export type SampleForTest = { html: string; receivedOn: string | null };

const ARRIVAL = 'arrival';

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

/**
 * "Start from a sample email": upload the .eml Gmail's "Download original" produces, and the
 * server reads it and proposes everything a template needs -- the sender domain Gmail authenticated,
 * the amounts and dates in it each with a pattern that reads exactly that value, and phrases for the
 * receipt marker. Choosing a value fills the matching field of the form below it; nothing is created
 * or saved here, and the existing Test then Activate steps are unchanged and still required.
 *
 * The best amount and date are chosen for the admin (the server ranks them), but every choice is
 * shown next to the text around it so it can be checked against the actual receipt, and the receipt
 * marker is never chosen for them: only the admin knows which phrase separates a receipt from the
 * same merchant's other mail.
 */
export function SampleEmailPanel({ onFill, onSample }: {
  onFill: (fill: SampleFill) => void;
  onSample: (sample: SampleForTest) => void;
}) {
  const id = useId();
  const { hasPermission } = useAdminAuth();
  const [analysis, setAnalysis] = useState<SampleAnalysis | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [amountIndex, setAmountIndex] = useState<number | null>(null);
  const [dateChoice, setDateChoice] = useState<number | typeof ARRIVAL | null>(null);
  const [marker, setMarker] = useState<string | null>(null);

  async function handleFile(file: File | undefined) {
    if (!file) return;
    setError(null);
    setFileName(file.name);
    setBusy(true);
    try {
      const raw = await readFileText(file);
      if (raw.length > MAX_EMAIL_CHARS) {
        setAnalysis(null);
        setError(EMAIL_TOO_LARGE);
        return;
      }
      const result = await adminMerchantTemplatesApi.analyzeSample(raw);
      setAnalysis(result);
      setMarker(null);

      // An amount is chosen for the admin only when the server is fairly sure it is the amount paid.
      // Where it is not (a layout whose labels do not sit next to their figures), guessing would put
      // a wrong amount in the form that looks as confident as a right one, so the admin picks.
      const amount = result.amounts.length > 0 && result.amounts[0].likelyTotal ? 0 : null;
      const date: number | typeof ARRIVAL | null = result.dates.length > 0 ? 0
        : (result.receivedOn ? ARRIVAL : null);
      setAmountIndex(amount);
      setDateChoice(date);

      onSample({ html: result.html, receivedOn: result.receivedOn });
      onFill({
        merchantDomain: result.authenticatedDomain ?? undefined,
        merchantName: result.senderName ?? undefined,
        amountPattern: amount !== null ? result.amounts[amount].pattern : undefined,
        datePattern: date === ARRIVAL ? result.arrivalDatePattern
          : date !== null ? result.dates[date].pattern : undefined,
      });
    } catch (err: any) {
      setAnalysis(null);
      setError(errorMessage(err, 'Could not read that email.'));
    } finally {
      setBusy(false);
    }
  }

  function chooseAmount(index: number) {
    if (!analysis) return;
    setAmountIndex(index);
    onFill({ amountPattern: analysis.amounts[index].pattern });
  }

  function chooseDate(choice: number | typeof ARRIVAL) {
    if (!analysis) return;
    setDateChoice(choice);
    onFill({ datePattern: choice === ARRIVAL ? analysis.arrivalDatePattern : analysis.dates[choice].pattern });
  }

  function chooseMarker(phrase: string) {
    setMarker(phrase);
    onFill({ receiptMarker: phrase });
  }

  return (
    <div className="bg-bg border border-border rounded-lg p-3.5 space-y-3">
      <div className="flex items-center gap-1.5">
        <FileUp size={13} className="text-primary" />
        <h4 className="text-xs font-semibold text-ink">Start from a sample email</h4>
      </div>
      <p className="text-[11px] text-muted">
        In Gmail, open a real receipt, then ⋮ → Show original → Download original, and choose that
        file here. Fynora reads it and suggests the fields below. Nothing is saved from the email.
      </p>
      <div className="flex items-center gap-2.5 flex-wrap">
        <label
          htmlFor={`${id}-file`}
          className="text-xs font-semibold text-primary bg-card border border-border hover:bg-white rounded-lg px-3 py-1.5 cursor-pointer"
        >
          {busy ? 'Reading…' : analysis ? 'Choose a different email' : 'Choose email file (.eml)'}
        </label>
        <input
          id={`${id}-file`}
          type="file"
          accept=".eml,message/rfc822,text/plain"
          aria-label="Sample email file"
          className="sr-only"
          disabled={busy}
          onChange={(e) => {
            void handleFile(e.target.files?.[0]);
            e.target.value = '';
          }}
        />
        {fileName && !error && <span className="text-[11px] text-muted">{fileName}</span>}
      </div>

      {error && <p role="alert" className="text-xs text-danger bg-danger-bg rounded-lg px-2.5 py-1.5">{error}</p>}

      {analysis && (
        <div className="space-y-3">
          <SenderLine analysis={analysis} canManageTrust={hasPermission('SYSTEM_SETTINGS')} />

          {analysis.problems.length > 0 && (
            <ul className="text-xs text-danger bg-danger-bg rounded-lg px-2.5 py-1.5 space-y-1">
              {analysis.problems.map((p) => (
                <li key={p} className="flex gap-1.5"><TriangleAlert size={13} className="flex-shrink-0 mt-0.5" />{p}</li>
              ))}
            </ul>
          )}

          <fieldset>
            <legend className="text-xs font-medium text-muted mb-1">Amount paid</legend>
            {analysis.amounts.length === 0 ? (
              <p className="text-xs text-muted">No amount found in this email.</p>
            ) : (
              amountIndex === null && (
                <p className="text-xs text-muted mb-1">
                  None of these clearly reads as the total. Choose the amount that was actually paid.
                </p>
              )
            )}
            {analysis.amounts.length > 0 && (
              <div className="space-y-1 max-h-56 overflow-y-auto">
                {analysis.amounts.map((c, i) => (
                  <label key={c.pattern} className="flex items-start gap-2 text-xs cursor-pointer rounded-lg px-2 py-1 hover:bg-card">
                    <input
                      type="radio"
                      name={`${id}-amount`}
                      checked={amountIndex === i}
                      onChange={() => chooseAmount(i)}
                      className="mt-0.5"
                    />
                    <span>
                      <span className="font-semibold text-ink">{c.value}</span>
                      {c.likelyTotal && <span className="ml-1.5 text-success">looks like the total</span>}
                      <span className="block text-muted font-mono break-all">…{c.context}…</span>
                    </span>
                  </label>
                ))}
              </div>
            )}
          </fieldset>

          <fieldset>
            <legend className="text-xs font-medium text-muted mb-1">Receipt date</legend>
            <div className="space-y-1 max-h-40 overflow-y-auto">
              {analysis.dates.map((c, i) => (
                <label key={c.pattern} className="flex items-start gap-2 text-xs cursor-pointer rounded-lg px-2 py-1 hover:bg-card">
                  <input
                    type="radio"
                    name={`${id}-date`}
                    checked={dateChoice === i}
                    onChange={() => chooseDate(i)}
                    className="mt-0.5"
                  />
                  <span>
                    <span className="font-semibold text-ink">{c.value}</span>
                    {!c.labelled && <span className="ml-1.5 text-muted">first date in the email</span>}
                    <span className="block text-muted font-mono break-all">…{c.context}…</span>
                  </span>
                </label>
              ))}
              {analysis.receivedOn && (
                <label className="flex items-start gap-2 text-xs cursor-pointer rounded-lg px-2 py-1 hover:bg-card">
                  <input
                    type="radio"
                    name={`${id}-date`}
                    checked={dateChoice === ARRIVAL}
                    onChange={() => chooseDate(ARRIVAL)}
                    className="mt-0.5"
                  />
                  <span>
                    <span className="font-semibold text-ink">The day the email arrived ({analysis.receivedOn})</span>
                    <span className="block text-muted">For receipts that print no date with a year.</span>
                  </span>
                </label>
              )}
            </div>
            {analysis.dates.length === 0 && !analysis.receivedOn && (
              <p className="text-xs text-muted">No date found, and the arrival day could not be read from this file.</p>
            )}
          </fieldset>

          {analysis.receiptMarkerSuggestions.length > 0 && (
            <div>
              <p className="text-xs font-medium text-muted mb-1">
                Receipt marker: a phrase every receipt from this merchant has and their other mail does not
              </p>
              <div className="flex flex-wrap gap-1.5">
                {analysis.receiptMarkerSuggestions.map((phrase) => (
                  <button
                    key={phrase}
                    type="button"
                    aria-pressed={marker === phrase}
                    onClick={() => chooseMarker(phrase)}
                    className={`text-xs rounded-full px-2.5 py-1 border ${
                      marker === phrase ? 'bg-primary text-on-primary border-primary' : 'bg-card text-ink border-border hover:bg-white'
                    }`}
                  >
                    {phrase}
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function SenderLine({ analysis, canManageTrust }: { analysis: SampleAnalysis; canManageTrust: boolean }) {
  if (!analysis.authenticatedDomain) {
    return (
      <p className="text-xs text-danger flex items-center gap-1.5">
        <ShieldAlert size={13} /> Gmail did not authenticate this email as coming from any domain.
      </p>
    );
  }
  return (
    <div className="text-xs space-y-1">
      <p className="flex items-center gap-1.5 text-ink">
        {analysis.domainIsTrusted ? <ShieldCheck size={13} className="text-success" /> : <ShieldAlert size={13} className="text-danger" />}
        Sent from <span className="font-mono font-semibold">{analysis.authenticatedDomain}</span>
        {analysis.domainIsTrusted ? <span className="text-success">-- trusted</span> : <span className="text-danger">-- not trusted yet</span>}
      </p>
      {analysis.handWrittenParserExists && (
        <p className="text-danger flex items-start gap-1.5">
          <TriangleAlert size={13} className="flex-shrink-0 mt-0.5" />
          {analysis.authenticatedDomain} already has a hand-written parser, so a template cannot be
          created for it. Changes to how it is read need an engineering release.
        </p>
      )}
      {!analysis.domainIsTrusted && (
        <p className="text-muted">
          A template for this domain will not run until the domain is trusted.{' '}
          {canManageTrust
            ? <Link to="/trusted-senders" className="text-primary underline">Trust it on the Trusted Senders page</Link>
            : 'Ask an admin with Settings access to trust it on the Trusted Senders page.'}
        </p>
      )}
    </div>
  );
}
