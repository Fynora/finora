import { useState, type FormEvent } from 'react';
import { Sparkles } from 'lucide-react';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useRobotsNoindex } from '../hooks/useRobotsNoindex';
import { sendStatementApi } from '../api/endpoints';
import { importFailureMessage, SERVER_FAILURE_MESSAGE } from '../api/importFailureMessages';

/** The code the phone shows: 6 characters, no I, L, O, 0 or 1 (StatementSendService). Spaces and
 *  dashes are dropped and case ignored, as the server does. */
const normalise = (typed: string) => typed.replace(/[\s-]/g, '').toUpperCase();

/** Spec §7: the password is never typed on a public page, and the queue cannot wait for one later.
 *  Sid (16 Oct): never suggest saving a copy without the password. */
const LOCKED_PDF_MESSAGE = 'This file has a password. Send it to your phone and share it into Fynora instead.';
const UNREACHABLE_MESSAGE = "Couldn't reach Fynora. Check your connection and try again.";

/**
 * Gate 2b spec §7: app.fynora.net/send. Public, no sign-in: a code from the user's phone and one
 * file. The page shows nothing about any account, and never asks the server for one. The file goes
 * through the normal queued import for the code's owner, who reviews it on their phone.
 */
export default function SendStatement() {
  useDocumentTitle('Send a statement — Fynora');
  useRobotsNoindex();
  const [code, setCode] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [sending, setSending] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const ready = normalise(code).length === 6 && file !== null && !sending;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!ready || !file) return;
    setSending(true);
    setError(null);
    try {
      await sendStatementApi.send(normalise(code), file);
      setSent(true);
    } catch (err: any) {
      const response = err?.response;
      const errorCode: string | undefined = response?.data?.errorCode ?? undefined;
      if (!response) setError(UNREACHABLE_MESSAGE);
      else if (errorCode === 'IMPORT_008') setError(LOCKED_PDF_MESSAGE);
      else setError(importFailureMessage(errorCode) ?? response.data?.message ?? SERVER_FAILURE_MESSAGE);
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg px-4">
      <div className="bg-card rounded-xl2 p-8 w-full max-w-sm shadow-soft border border-border">
        <div className="flex items-center gap-2 mb-6">
          <span className="w-7 h-7 rounded-lg bg-gradient-to-br from-primary to-primary-dark flex items-center justify-center">
            <Sparkles size={14} className="text-on-primary" strokeWidth={2.5} />
          </span>
          <span className="font-display font-extrabold tracking-wide text-ink">FYNORA</span>
        </div>

        <h1 className="text-2xl font-bold mb-5 text-ink">Send a statement to Fynora</h1>

        {sent ? (
          <p role="status" className="text-sm text-ink leading-relaxed">
            Sent. Open Fynora on your phone to review it.
          </p>
        ) : (
          <form onSubmit={handleSubmit} className="space-y-4" noValidate>
            <div>
              <label htmlFor="send-code" className="block text-sm font-medium text-ink mb-1">
                Code from your phone
              </label>
              <input
                id="send-code"
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
                autoComplete="off"
                autoCapitalize="characters"
                spellCheck={false}
                maxLength={9}
                className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2.5 font-mono text-lg tracking-widest focus:outline-none focus:ring-2 focus:ring-primary/30"
              />
            </div>
            <div>
              <label htmlFor="send-file" className="block text-sm font-medium text-ink mb-1">
                Statement file (PDF or CSV)
              </label>
              <input
                id="send-file"
                type="file"
                accept=".pdf,.csv,application/pdf,text/csv"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
                className="w-full text-sm text-ink"
              />
            </div>
            {error && (
              <p role="alert" className="text-sm text-danger">
                {error}
              </p>
            )}
            <button
              type="submit"
              disabled={!ready}
              className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
            >
              {sending ? 'Sending…' : 'Send'}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
