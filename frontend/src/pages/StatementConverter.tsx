import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { CheckCircle2, Download, FileSpreadsheet, Lock, ShieldCheck } from 'lucide-react';
import { INLINE_LINK, PublicLayout } from '../components/PublicLayout';
import { converterApi } from '../api/endpoints';
import { downloadBlob } from '../lib/download';
import { XLSX_MIME } from '../lib/xlsx';
import {
  CHECKED_NOTE,
  CONSENT_LABEL,
  CONVERTER_DESCRIPTION,
  CONVERTER_FAQ,
  CONVERTER_INTRO,
  CONVERTER_TITLE,
  FILE_NOTICE,
  MAX_MEGABYTES,
  MAX_PAGES,
  NOT_AFFILIATED,
  STEPS,
  TESTED_BANKS,
} from './converter/converterContent';
import {
  COLUMNS,
  csvOf,
  fileBaseName,
  indianDate,
  rowCount,
  sectionLabel,
  xlsxOf,
  type ConvertedStatement,
} from './converter/statementSheets';
import { useTurnstile } from './converter/useTurnstile';

const MAX_BYTES = MAX_MEGABYTES * 1024 * 1024;
const PREVIEW_ROWS = 8;

const UNREACHABLE = "Couldn't reach Fynora. Check your connection and try again.";
const UNAVAILABLE = 'The free converter isn’t available right now. Please come back a little later.';
const NOT_A_PDF = 'Choose a PDF statement. The free converter reads PDF files only.';
const TOO_LARGE = `This file is over ${MAX_MEGABYTES} MB. The free converter takes statements up to ${MAX_MEGABYTES} MB.`;
const LOCKED = 'This statement is locked. Type the password you use to open it.';
const WRONG_PASSWORD = 'That password didn’t open the file. Check it and try again.';
const CHECK_FAILED = 'We couldn’t confirm you’re a person. Wait for the check below to finish, then try again.';

/** The file as base64, without the data-URL prefix. */
function readAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const url = String(reader.result ?? '');
      resolve(url.slice(url.indexOf(',') + 1));
    };
    reader.onerror = () => reject(reader.error ?? new Error('could not read the file'));
    reader.readAsDataURL(file);
  });
}

function formatAmount(value: number | null): string {
  if (value === null || value === undefined) return '';
  return value.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

type Availability = 'checking' | 'on' | 'off';

/**
 * The tool. Rendered by the prerender too (no effect runs there), so its first render is the form
 * as a visitor first sees it: consent box unticked, nothing else usable yet.
 */
export function ConverterTool() {
  const siteKey = import.meta.env.VITE_TURNSTILE_SITE_KEY;
  const [availability, setAvailability] = useState<Availability>('checking');
  const [consent, setConsent] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [needsPassword, setNeedsPassword] = useState(false);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ConvertedStatement | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);
  const turnstile = useTurnstile(siteKey, availability === 'on' && result === null);

  useEffect(() => {
    if (!siteKey) {
      setAvailability('off');
      return;
    }
    let cancelled = false;
    converterApi
      .availability()
      .then((a) => !cancelled && setAvailability(a.available ? 'on' : 'off'))
      .catch(() => !cancelled && setAvailability('off'));
    return () => {
      cancelled = true;
    };
  }, [siteKey]);

  function chooseFile(e: ChangeEvent<HTMLInputElement>) {
    const chosen = e.target.files?.[0] ?? null;
    setError(null);
    setNeedsPassword(false);
    setPassword('');
    if (!chosen) {
      setFile(null);
      return;
    }
    if (!/\.pdf$/i.test(chosen.name) && chosen.type !== 'application/pdf') {
      setFile(null);
      setError(NOT_A_PDF);
      return;
    }
    if (chosen.size > MAX_BYTES) {
      setFile(null);
      setError(TOO_LARGE);
      return;
    }
    setFile(chosen);
  }

  const ready =
    availability === 'on' && consent && file !== null && turnstile.token !== null && !busy &&
    (!needsPassword || password.length > 0);

  async function convert(e: FormEvent) {
    e.preventDefault();
    if (!ready || !file || !turnstile.token) return;
    setBusy(true);
    setError(null);
    try {
      const base64 = await readAsBase64(file);
      const converted = await converterApi.convert({
        file: base64,
        ...(needsPassword ? { password } : {}),
        turnstileToken: turnstile.token,
        consent: true,
      });
      setResult(converted);
      setPassword('');
    } catch (err: any) {
      const response = err?.response;
      const code: string | undefined = response?.data?.errorCode;
      if (!response) setError(UNREACHABLE);
      else if (code === 'IMPORT_008') {
        setNeedsPassword(true);
        setError(LOCKED);
      } else if (code === 'IMPORT_009') setError(WRONG_PASSWORD);
      else if (code === 'CONVERT_001') {
        setAvailability('off');
      } else if (code === 'CONVERT_003') setError(CHECK_FAILED);
      else setError(response.data?.message ?? 'We couldn’t convert this statement. Please try again.');
      // The password is not kept between attempts beyond this form, and never after a success.
    } finally {
      setBusy(false);
      // A Turnstile token is single-use: ask for a fresh one for the next attempt.
      turnstile.reset();
    }
  }

  function startAgain() {
    setResult(null);
    setFile(null);
    setPassword('');
    setNeedsPassword(false);
    setError(null);
    if (fileInput.current) fileInput.current.value = '';
  }

  function downloadCsv() {
    if (!result) return;
    downloadBlob(new Blob([csvOf(result)], { type: 'text/csv;charset=utf-8' }), `${fileBaseName(result)}.csv`);
  }

  function downloadXlsx() {
    if (!result) return;
    downloadBlob(new Blob([xlsxOf(result, CHECKED_NOTE)], { type: XLSX_MIME }), `${fileBaseName(result)}.xlsx`);
  }

  if (result) {
    const total = rowCount(result);
    const multi = result.sections.length > 1;
    return (
      <section aria-labelledby="converter-result" className="bg-card border border-border rounded-xl2 p-6 shadow-soft">
        <h2 id="converter-result" className="flex items-center gap-2 text-xl font-bold text-ink mb-2">
          <CheckCircle2 size={20} className="text-success" /> Your file is ready
        </h2>
        <p className="text-sm text-muted mb-4">
          {total} transaction{total === 1 ? '' : 's'}
          {result.sections.map((section, i) => (
            <span key={i}>
              {' · '}
              {sectionLabel(section, i)}
              {section.periodStart && section.periodEnd
                ? `, ${indianDate(section.periodStart)} to ${indianDate(section.periodEnd)}`
                : ''}
            </span>
          ))}
        </p>
        <p className="text-sm text-ink bg-primary-light rounded-lg px-3 py-2 mb-4">{CHECKED_NOTE}</p>

        <div className="overflow-x-auto mb-5">
          <table className="w-full text-sm">
            <caption className="sr-only">The first {PREVIEW_ROWS} transactions</caption>
            <thead>
              <tr className="text-left text-muted">
                {multi && <th className="py-1 pr-3 font-medium">Account</th>}
                {COLUMNS.map((c) => (
                  <th key={c} className={`py-1 pr-3 font-medium ${c === 'Debit' || c === 'Credit' || c === 'Balance' ? 'text-right' : ''}`}>
                    {c}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {result.sections
                .flatMap((section, i) => section.rows.map((row) => ({ row, label: sectionLabel(section, i) })))
                .slice(0, PREVIEW_ROWS)
                .map(({ row, label }, i) => (
                  <tr key={i} className="border-t border-border">
                    {multi && <td className="py-1 pr-3">{label}</td>}
                    <td className="py-1 pr-3 whitespace-nowrap">{indianDate(row.date)}</td>
                    <td className="py-1 pr-3">{row.description}</td>
                    <td className="py-1 pr-3 text-right tabular-nums">{formatAmount(row.debit)}</td>
                    <td className="py-1 pr-3 text-right tabular-nums">{formatAmount(row.credit)}</td>
                    <td className="py-1 pr-3 text-right tabular-nums">{formatAmount(row.balance)}</td>
                    <td className="py-1 pr-3">{row.reference}</td>
                  </tr>
                ))}
            </tbody>
          </table>
          {total > PREVIEW_ROWS && (
            <p className="text-xs text-muted mt-2">Showing the first {PREVIEW_ROWS} of {total}. The file has them all.</p>
          )}
        </div>

        <div className="flex flex-wrap gap-3">
          <button type="button" onClick={downloadXlsx}
            className="inline-flex items-center gap-2 bg-primary hover:bg-primary-dark text-on-primary rounded-lg px-4 py-2.5 text-sm font-semibold">
            <FileSpreadsheet size={16} /> Download Excel
          </button>
          <button type="button" onClick={downloadCsv}
            className="inline-flex items-center gap-2 border border-border text-ink rounded-lg px-4 py-2.5 text-sm font-semibold hover:bg-bg">
            <Download size={16} /> Download CSV
          </button>
          <button type="button" onClick={startAgain} className="text-sm text-muted hover:text-ink underline px-2">
            Convert another statement
          </button>
        </div>

        <div className="mt-6 border-t border-border pt-5">
          <p className="font-semibold text-ink mb-1">Do this every month without converting</p>
          <p className="text-sm text-muted mb-3">
            Fynora reads your statements, sorts your spending into categories and shows where your money went.
          </p>
          <Link to="/auth" className="text-sm font-semibold text-primary hover:underline">
            Create a free account
          </Link>
        </div>
      </section>
    );
  }

  const unavailable = availability === 'off';
  return (
    <form onSubmit={convert} noValidate aria-labelledby="converter-form"
      className="bg-card border border-border rounded-xl2 p-6 shadow-soft space-y-5">
      <h2 id="converter-form" className="text-xl font-bold text-ink">Convert your statement</h2>

      {unavailable && (
        <p role="status" className="text-sm text-ink bg-bg border border-border rounded-lg px-3 py-2">{UNAVAILABLE}</p>
      )}

      <div className="text-sm text-muted bg-bg border border-border rounded-lg px-3 py-3 flex gap-2">
        <ShieldCheck size={18} className="text-primary shrink-0 mt-0.5" />
        <p><strong className="text-ink">What happens to your file:</strong> {FILE_NOTICE}{' '}
          <Link to="/privacy#converter" className={INLINE_LINK}>Privacy policy</Link>
        </p>
      </div>

      <label className="flex gap-3 items-start text-sm text-ink cursor-pointer">
        <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)}
          disabled={unavailable} className="mt-1 h-4 w-4" />
        <span>{CONSENT_LABEL}</span>
      </label>

      <div>
        <label htmlFor="converter-file" className="block text-sm font-medium text-ink mb-1">
          Statement PDF (up to {MAX_PAGES} pages, {MAX_MEGABYTES} MB)
        </label>
        <input id="converter-file" ref={fileInput} type="file" accept=".pdf,application/pdf"
          onChange={chooseFile} disabled={!consent || unavailable || busy} className="w-full text-sm text-ink" />
      </div>

      {needsPassword && (
        <div>
          <label htmlFor="converter-password" className="flex items-center gap-1.5 text-sm font-medium text-ink mb-1">
            <Lock size={14} /> PDF password
          </label>
          <input id="converter-password" type="password" value={password} autoComplete="off"
            onChange={(e) => setPassword(e.target.value)}
            className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30" />
          <p className="text-xs text-muted mt-1">Used once to open this file. Never saved.</p>
        </div>
      )}

      {/* Cloudflare Turnstile renders here once the converter is available. */}
      <div ref={turnstile.container} data-testid="turnstile" />
      {turnstile.state === 'failed' && (
        <p role="alert" className="text-sm text-danger">The person check couldn’t load. Refresh the page and try again.</p>
      )}

      {error && <p role="alert" className="text-sm text-danger">{error}</p>}

      <button type="submit" disabled={!ready}
        className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50">
        {busy ? 'Converting…' : 'Convert to Excel or CSV'}
      </button>
    </form>
  );
}

/**
 * The free bank statement to Excel/CSV converter: app.fynora.net/bank-statement-to-excel.
 * Prerendered (scripts/ssr-entry.tsx), in the sitemap, with FAQPage markup from the same FAQ it
 * shows (src/lib/structuredData.ts). Design 2026-10-31, approved by Sid.
 */
export default function StatementConverter() {
  return (
    <PublicLayout title={CONVERTER_TITLE} subtitle={CONVERTER_INTRO} description={CONVERTER_DESCRIPTION}>
      <div className="space-y-14">
        <ConverterTool />

        <section aria-labelledby="converter-how">
          <h2 id="converter-how" className="text-2xl font-bold text-ink mb-5">How it works</h2>
          <ol className="grid gap-4 md:grid-cols-3">
            {STEPS.map(([title, text], i) => (
              <li key={title} className="bg-card border border-border rounded-xl2 p-5">
                <span className="text-xs font-semibold text-primary">Step {i + 1}</span>
                <h3 className="font-semibold text-ink mt-1 mb-1">{title}</h3>
                <p className="text-sm text-muted">{text}</p>
              </li>
            ))}
          </ol>
        </section>

        <section aria-labelledby="converter-banks">
          <h2 id="converter-banks" className="text-2xl font-bold text-ink mb-3">Banks we’ve tested</h2>
          <p className="text-sm text-muted mb-4">
            We’ve converted real statements from these banks and card issuers. Statements from other Indian banks may work too.
          </p>
          <ul className="flex flex-wrap gap-2 mb-3">
            {TESTED_BANKS.map((bank) => (
              <li key={bank} className="text-sm text-ink bg-card border border-border rounded-full px-3 py-1">{bank}</li>
            ))}
          </ul>
          <p className="text-xs text-muted">{NOT_AFFILIATED}</p>
        </section>

        <section aria-labelledby="converter-faq">
          <h2 id="converter-faq" className="text-2xl font-bold text-ink mb-5">Questions</h2>
          <dl className="space-y-5">
            {CONVERTER_FAQ.map(([question, answer]) => (
              <div key={question}>
                <dt className="font-semibold text-ink mb-1">{question}</dt>
                <dd className="text-sm text-muted leading-relaxed">{answer}</dd>
              </div>
            ))}
          </dl>
        </section>
      </div>
    </PublicLayout>
  );
}
