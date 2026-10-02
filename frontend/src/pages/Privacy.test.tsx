import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import Privacy from './Privacy';

function policyText(): string {
  render(
    <MemoryRouter>
      <Privacy />
    </MemoryRouter>
  );
  return document.body.textContent ?? '';
}

/**
 * The public page makes claims about AI and shared learning. The policy must say the same
 * things, or the page outruns it. Gmail sync is paused and was never offered to users
 * (docs/engineering/gmail-sync-paused.md), so the policy must not mention Gmail at all. Each assertion
 * below is a fact read from code:
 *   - hand-typed transaction to the AI: TransactionService -> CategorizationService.suggest ->
 *     UserMerchantCategoryResolutionService (sends the description, never a person payment, with ids
 *     redacted and recognised names replaced, plus the user's category names; the description is free
 *     text, so the policy must not claim it holds no identifying detail).
 *   - Insights summary: FynInsightsNarrationService (category-level totals and category names).
 *   - name shielding: FynNameShield (profile and account holder names, people in person payments,
 *     names in a screenshot's text).
 *   - shared learning: SharedCorpusService (>= 3 distinct users, only vpa:-keyed payees the
 *     classifier calls BUSINESS or FINANCIAL_INSTITUTION), SharedCorpusRetentionSweepService (only
 *     keys with one or two voters and no shared row, 180 / 365 days from the most recent entry), and
 *     AccountPurgeSweepService (deletes the user's own observations only).
 *   - the classifier is a heuristic and has been wrong before, so "individuals are never recorded"
 *     would promise an outcome the code cannot guarantee.
 */
describe('Privacy policy matches what the product does', () => {
  it('discloses that a hand-typed transaction can reach the AI for categorisation', () => {
    expect(policyText()).toMatch(/add a transaction by hand without choosing a category/i);
  });

  it('says the typed description is free text, and that importing a statement does not do this', () => {
    const t = policyText();
    expect(t).toMatch(/description is free text and may contain personal information/i);
    expect(t).toMatch(/Importing a statement does not do this/i);
    expect(t).not.toMatch(/without your amount, account or any other identifying detail/i);
    // The stored value is whatever the service returns; the code does not guarantee it is a merchant description.
    expect(t).toMatch(/short description returned by the service is stored\s+against the payee ID/i);
    expect(t).not.toMatch(/short description of the merchant/i);
  });

  it('no longer claims categorisation never involves a third-party AI', () => {
    const t = policyText();
    expect(t).not.toMatch(/not a third-party AI service — see\s+Uploaded Statements above/);
    expect(t).toMatch(/except for the three uses of an AI service/i);
  });

  it('describes learning across users: what is recorded, the threshold, what is shared, and deletion', () => {
    const t = policyText();
    expect(t).toMatch(/several separate users/i);
    expect(t).toMatch(/payee ID/i);
    expect(t).toMatch(/does not include your transaction amounts, transaction dates, account ID or statement files/i);
    expect(t).toMatch(/If you delete your account, the entries in the log that belong to your account/i);
    // Only what the code shows: a separate table with no account ID, untouched by the purge. No stated reason.
    expect(t).toMatch(
      /Shared suggestions are stored separately from\s+individual user log entries, hold no account ID, and are not removed when an account is deleted/i
    );
    expect(t).not.toMatch(/because they are about\s+the payee/i);
  });

  it('does not promise that individuals are never recorded, because the classifier can be wrong', () => {
    const t = policyText();
    expect(t).not.toMatch(/never recorded/i);
    expect(t).toMatch(/classification is automatic and can be wrong/i);
    expect(t).toMatch(/classified as an individual, or not classified, are not recorded/i);
  });

  it('discloses the retention that applies and the entries that have no scheduled deletion', () => {
    const t = policyText();
    expect(t).toMatch(/180 days after the most recent entry/i);
    expect(t).toMatch(/365 days after the most recent entry/i);
    expect(t).toMatch(/three or more users but no shared\s+suggestion, currently have no scheduled deletion date/i);
  });

  it('lists Anthropic as a provider, and covers AI features in the cross-border note', () => {
    const t = policyText();
    expect(t).toMatch(/Anthropic\s*—\s*the AI service behind Ask Fyn/i);
    expect(t).toMatch(/authentication, communications and AI\s+features are also based outside India/i);
  });

  // AI name shielding (PR #1894): what each AI use sends, and that recognised names are replaced.
  it('names every AI use and what each one sends', () => {
    const t = policyText();
    expect(t).toMatch(/On the Insights page, Fyn writes a short\s+summary of your month from category-level totals and your category names/i);
    expect(t).toMatch(/the\s+description you typed and the names of your categories may be sent/i);
    expect(t).toMatch(/A\s+description that is a payment to a person is never sent/i);
    expect(t).toMatch(/reads its text on its own servers and sends that\s+text, not the image/i);
  });

  it('says recognised names are replaced with placeholders, and which names are not recognised', () => {
    const t = policyText();
    expect(t).toMatch(/Fynora hides the names of people it can recognise before anything is sent/i);
    expect(t).toMatch(/Anthropic receives the placeholder, never the\s+name/i);
    expect(t).toMatch(/common first names and\s+surnames from a list built into Fynora, even for someone you have never paid/i);
    expect(t).toMatch(/A name Fynora cannot recognise is sent\s+as written/i);
  });

  // Statement refresh and saved statement passwords (docs: statement refresh design, 2026-09-27).
  // These describe the feature as designed; this text must ship together with it, not before.
  it('says a statement password is kept only with consent, per statement, and can be withdrawn', () => {
    const t = policyText();
    expect(t).toMatch(/does not keep the password you type to open one\s+unless you agree to it/i);
    expect(t).toMatch(/applies to that one\s+statement only/i);
    expect(t).toMatch(/If you choose not to save it, nothing is stored/i);
    expect(t).toMatch(/Settings → Data → Saved statement passwords, which\s+deletes it immediately/i);
    expect(t).toMatch(/password itself is never shown to you or to Fynora staff, and it is never written to logs/i);
  });

  it('says staff reviewing a held statement can open an unlocked copy of one whose password was saved', () => {
    const t = policyText();
    expect(t).toMatch(/to import it,\s+to refresh it, and, if we could not read it automatically, to let authorized staff review it/i);
    expect(t).toMatch(/authorized staff reviewing it can open an unlocked copy so they can read its contents/i);
    expect(t).toMatch(/created only for that review and is never stored, and every\s+such access is logged/i);
  });

  it('says a refresh uses the stored files, runs only when the user starts it, and keeps their edits', () => {
    const t = policyText();
    expect(t).toMatch(/refresh the transactions from your statements\s+using these stored files/i);
    expect(t).toMatch(/A refresh runs only when\s+you choose to start it/i);
    expect(t).toMatch(/A transaction you edited keeps your changes/i);
  });

  it('does not mention Gmail or Google mailbox access while Gmail sync is paused and unreleased', () => {
    const t = policyText();
    expect(t).not.toMatch(/gmail/i);
    expect(t).not.toMatch(/mailbox/i);
    expect(t).not.toMatch(/Limited Use/i);
    expect(t).not.toMatch(/Google API Services User Data/i);
  });

  // UserActivityInterceptor writes one user_activity_days row (V249) per user per calendar day,
  // keyed by user id, and AccountPurgeSweepService deletes them. That is per-user data, so the
  // "aggregated, non-identifying" analytics sentence alone would understate it.
  it('discloses the per-account record of which days the app was used, and that it is deleted with the account', () => {
    const t = policyText();
    expect(t).toMatch(/record the dates on which you use Fynora/i);
    expect(t).toMatch(/calendar date only, once per day, not\s+the time or what you did/i);
    expect(t).toMatch(/linked to your account and are deleted if you delete your account/i);
  });

});
