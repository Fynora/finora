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
 *   - AI insights (the month story and "Why?"): AiInsightFacts is an allowlist of figures and
 *     built-in category names; a category the user created is sent as a placeholder. Texts, the
 *     figures behind them, thumbs and the monthly allowance live in ai_insight_texts and
 *     ai_insight_usage (V283), which AccountPurgeSweepService deletes with the account.
 *     Anthropic's terms as checked on 2026-10-22 (spec §9).
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
  it('discloses the setup answers it collects, the required spending question included', () => {
    expect(policyText()).toMatch(/your answers to the short questions when you first set up Fynora: how you\s+keep track of your spending today, which everyone is asked once/i);
  });

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

  // Gate 2b (spec §5.1, Sid's decision (a), 16 Oct). Each clause traced to code:
  //   - 90 days: StatementStorageSweepService, app.statement-storage.sweep.retention-days (90; unset
  //     in production, so the default applies), counted from statement_imports.deleted_at.
  //   - a failed import's file: a FAILED import_jobs row protects its object until the account is
  //     purged (the sweep's "Accepted trade-off"); dismissing it from the list keeps the row.
  //   - the exception: a PDF whose password was typed but not kept is staged synchronously
  //     (Import.tsx / ImportScreen.tsx: queued only when there is no password or it is kept), and a
  //     synchronous failure stores no file. Wording chosen by Sid, 2026-10-25.
  //   - account deletion: AccountPurgeSweepService reclaims the user's objects at purge.
  it('says how long a deleted statement\'s file stays in storage', () => {
    expect(policyText().replace(/\s+/g, ' ')).toContain(
      'When you delete a statement, its original file stays in our storage for 90 days and is then deleted. '
        + 'If an import could not be completed, we keep its file so it can be retried without uploading it '
        + 'again, until you delete your account. The one exception is a password-protected PDF whose password '
        + 'you chose not to save: if its import fails, the file is not kept. When you delete your Fynora '
        + 'account, your statement files are deleted with it.');
  });

  it('describes the free converter: nothing kept, deleted within 60 seconds, address for 24 hours, no AI, US servers', () => {
    policyText();
    const section = document.getElementById('converter')?.textContent ?? '';
    expect(section).toMatch(/^Free Statement Converter/);
    expect(section).toMatch(/We do not save the file, its password, or the transactions in it/);
    expect(section).toMatch(/within 60 seconds/);
    expect(section).toMatch(/up to 24 hours/);
    expect(section).toMatch(/not sent to any AI\s+service/);
    expect(section).toMatch(/outside India \(in the United States/);
    expect(section).toMatch(/nothing that identifies you or your statement/);
  });

  it('gives Uploaded Statements an anchor the import screens link to', () => {
    policyText();
    expect(document.getElementById('uploaded-statements')?.textContent).toMatch(/^Uploaded Statements/);
  });

  it('discloses the retention that applies and the entries that have no scheduled deletion', () => {
    const t = policyText();
    expect(t).toMatch(/180 days after the most recent entry/i);
    expect(t).toMatch(/365 days after the most recent entry/i);
    expect(t).toMatch(/three or more users but no shared\s+suggestion, currently have no scheduled deletion date/i);
  });

  // SmsProperties / TwoFactorSmsProvider.sendTransactionAlert: 2Factor sends only the alert for a
  // hand-added transaction (TransactionService.doSendTransactionAlert, verified phone only), and
  // phone-verification codes go through Firebase Phone Authentication.
  it('describes TwoFactor as transaction alerts, not verification codes', () => {
    const t = policyText();
    expect(t).not.toMatch(/SMS\/OTP delivery for phone verification/i);
    expect(t).toMatch(/TwoFactor\s*—\s*SMS alerts for transactions you add by hand, sent only to a verified phone number/i);
    expect(t).toMatch(/the amount, whether it was debited or credited, and the description you typed/i);
    expect(t).toMatch(/phone-verification codes are sent by Firebase Authentication, not TwoFactor/i);
  });

  // BankLogo asks img.logo.dev for the bank's own domain; MerchantLogo (web and mobile) asks only for
  // the server's `logoDomain`, a web address from MerchantBrands. Tokens: VITE_LOGODEV_TOKEN is in
  // the production web build, EXPO_PUBLIC_LOGODEV_TOKEN in the EAS production environment. Mobile
  // builds released before this change looked the merchant name up, and stay installed until people
  // update, so the line says so rather than promising no name was ever sent.
  it('lists Logo.dev, on the website and in the app, and says a request carries only a web address', () => {
    const t = policyText();
    expect(t).not.toMatch(/sending the bank's web address or the merchant name shown/i);
    expect(t).toMatch(/Logo\.dev\s*—\s*bank and merchant logos on the website, and merchant logos in the mobile app/i);
    expect(t).toMatch(/asks Logo\.dev for it directly by web address: the bank's, or a well-known merchant's from a fixed list Fynora keeps/i);
    expect(t).toMatch(/Earlier versions of the mobile app sent the merchant name shown instead/i);
    expect(t).toMatch(/along with your IP address and browser or device details/i);
  });

  it('lists Anthropic as a provider, and covers AI features in the cross-border note', () => {
    const t = policyText();
    expect(t).toMatch(/Anthropic\s*—\s*the AI service behind Ask Fyn/i);
    expect(t).toMatch(/authentication, communications and AI\s+features are also based outside India/i);
  });

  // AI name shielding (PR #1894): what each AI use sends, and that recognised names are replaced.
  it('names every AI use and what each one sends', () => {
    const t = policyText();
    expect(t).not.toMatch(/your category names/i);
    expect(t).toMatch(/AI insights, described below/i);
    expect(t).toMatch(/the\s+description you typed and the names of your categories may be sent/i);
    expect(t).toMatch(/A\s+description that is a payment to a person is never sent/i);
    expect(t).toMatch(/reads its text on its own servers and sends that\s+text, not the image/i);
  });

  // AI insights spec §8.4: what is sent, history not advice, what is stored and its deletion, and
  // Anthropic's standard terms.
  it('describes AI insights: what is sent and never sent, history not advice, storage, and the provider terms', () => {
    const t = policyText();
    // FactSheet.modelJson: the month, subjects (built-in names or placeholders), facts' display forms.
    expect(t).toMatch(/only figures from your own history: the month's amounts, counts and percentages, and the names\s+of Fynora's built-in categories/i);
    // ai_insight_usage counts both stories and "Why?" answers; AiInsightGenerator writes an
    // ai_audit_log row per request with no prompt or reply text; both go with the account.
    expect(t).toMatch(/how many AI texts were written for you this month/i);
    expect(t).toMatch(/a record of each request to the AI: the model, its cost, how long it took and whether the reply\s+passed the checks, never the text/i);
    expect(t).toMatch(/never sends payee or people's names, the names of categories you create/i);
    expect(t).toMatch(/account details or transaction descriptions/i);
    expect(t).toMatch(/describes what happened in your past spending; it does not give financial advice/i);
    expect(t).toMatch(/Fynora fills in every figure and name itself/i);
    expect(t).toMatch(/marked "Written by AI"/i);
    expect(t).toMatch(/all of this is deleted if you delete your account/i);
    expect(t).toMatch(/under Anthropic's standard commercial terms/i);
    expect(t).toMatch(/does not train its models on it/i);
    expect(t).toMatch(/deletes it within 30 days/i);
    expect(t).toMatch(/up to 2 years if it is flagged for misuse/i);
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

  it('says a refresh uses the stored files, runs when the user starts it, and keeps their edits', () => {
    const t = policyText();
    expect(t).toMatch(/refresh the transactions from your statements\s+using these stored files/i);
    expect(t).toMatch(/A refresh runs when you\s+choose to start it/i);
    expect(t).toMatch(/a transaction you edited keeps your changes/i);
  });

  // AdminStatementCorrectionService: support can apply a checked correction for the user, audited,
  // and the user is told what changed. The policy must not still promise "only when you start it".
  it('says support may apply a checked correction for the user, who is told, and that it is logged', () => {
    const t = policyText();
    expect(t).not.toMatch(/runs only when/i);
    expect(t).toMatch(/our support team may also apply it for you/i);
    expect(t).toMatch(/told by push notification and by email what changed/i);
    expect(t).toMatch(/Every such action is logged\s+and auditable/i);
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
  // statement_analysis_sessions holds one row per upload attempt, keyed by user id -- read, failed,
  // or (since V267) refused before reading. A refused row has no file name; a failed one's
  // failure_detail can quote a fragment of the document (StatementAnalysisRecorder.truncate), so
  // the page must not claim the record never holds statement text. On deletion the rows stay, with
  // user_id, file_name, failure_detail and content_hash nulled (anonymizeByUserId).
  it('discloses the per-upload technical record, including uploads turned away before reading', () => {
    const t = policyText();
    expect(t).toMatch(/keeps a short technical record of that attempt/i);
    expect(t).toMatch(/turned away before it was\s+read/i);
    expect(t).toMatch(/a locked PDF sent without its password/i);
    expect(t).toMatch(/may quote a short piece of the text that caused the problem/i);
    expect(t).toMatch(/turned away before\s+reading, the record holds nothing about the file/i);
    expect(t).toMatch(/kept without your identity: the link to your\s+account, the file name and the recorded reason text are removed/i);
  });

  it('discloses the per-account record of which days the app was used, and that it is deleted with the account', () => {
    const t = policyText();
    expect(t).toMatch(/record the dates on which you use Fynora/i);
    expect(t).toMatch(/calendar date only, once per day, not\s+the time or what you did/i);
    expect(t).toMatch(/linked to your account and are deleted if you delete your account/i);
  });

  it('discloses the import-funnel dates: which three moments, the date only, and deletion with the account', () => {
    const t = policyText();
    expect(t).toMatch(/opened the import screen, chose a file to upload and opened\s+an import's review/i);
    expect(t).toMatch(/any answer you tap when Fynora asks what stopped you/i);
    expect(t).toMatch(/We record the date\s+only, never the time, the file or anything in it/i);
    expect(t).toMatch(/where adding a statement gets\s+difficult/i);
  });

  // Gate 2a spec §8: the deletion reason is kept unlinked, so the page says it outlives the account.
  it('discloses that a deletion reason is kept with the month only and no link to the person', () => {
    expect(policyText()).toMatch(
      /If you tell us why you are deleting your account, we keep that answer with the month only and no\s+link to you or your account, so it is not deleted with your account\./);
  });

  // V286, V300: lib/signupAttribution.ts captures the referring host, the four utm tags and the first
  // public page (its route, from the build's marker) on a website sign-up; SignupAttributionService
  // stores the host and the path only, and AccountPurgeSweepService deletes the row with the account.
  // Privacy workstream 6. Each claim below matches code merged by workstreams 2-8:
  //   - consent box and acceptance record: TermsConsentService, user_terms_acceptances (V302), channel
  //     from X-Client-Platform; AI consent off until yes (FynAvailabilityGuard.aiConsentGiven).
  //   - switching AI off: TermsConsentService.eraseAiOutput deletes ai_insight_texts and clears
  //     ai_audit_log.tool_inputs/tool_outputs; chats are kept (Free daily count reads them).
  //   - the AI log holds each lookup's inputs and returned figures: FynChatOrchestrationService.writeAuditLog.
  //   - encryption: StatementContentService (confirmed files, V107), staged uploads (V304),
  //     saved passwords (StatementPasswordService), all EncryptionService AES-256-GCM.
  //   - deletion: StatementImport @SQLDelete scrub (#2428), Firebase phone user (#2429), Google and
  //     Apple deletion in both apps (#2435, #2437), store-subscription warning (#2442), account names
  //     cleared on purge (V304). Acceptance rows are not purged.
  describe('workstream 6: consent, AI choice, encryption and deletion', () => {
    it('describes the Terms box and the record kept of each acceptance', () => {
      const t = policyText();
      expect(t).toMatch(/asks you to tick a box agreeing to the Terms of Service and this policy when you sign up/i);
      expect(t).toMatch(/We record which\s+version you agreed to, when, and whether it was on the website, the iPhone app or the Android app/i);
    });

    it('no longer says withdrawing consent can only mean deleting the account', () => {
      const t = policyText();
      expect(t).not.toMatch(/withdrawing consent in practice means deleting your account/i);
      expect(t).toMatch(/AI features need a separate yes: they stay off unless you turn them on/i);
      expect(t).toMatch(/withdrawing consent to them means deleting your\s+account/i);
      expect(t).toMatch(/including consent to AI features \(Settings → Data → AI features\)/i);
    });

    it('explains the AI switch: off by default, where to change it, and what off means', () => {
      policyText();
      const section = document.getElementById('ai-features')?.textContent ?? '';
      expect(section).toMatch(/^AI Features: Your Choice/);
      expect(section).toMatch(/they are off unless you turn them on/);
      expect(section).toMatch(/Settings → Data → AI features/);
      expect(section).toMatch(/nothing about you is sent to Anthropic: Ask Fyn does not answer/);
    });

    it('says what turning AI off deletes and what it keeps', () => {
      policyText();
      const section = (document.getElementById('ai-features')?.textContent ?? '').replace(/\s+/g, ' ');
      expect(section).toContain('Fynora deletes the AI-written insight texts it stored for you');
      expect(section).toContain('the figures that were sent to the AI and returned to it');
      expect(section).toContain('your Ask Fyn conversations, which it uses to count the Free plan\'s daily questions');
      expect(section).toContain('We keep a record of each time you turn AI features on or off.');
    });

    it('says the AI log holds the figures a lookup returned, not only which lookup ran', () => {
      const t = policyText();
      expect(t).not.toMatch(/only\s+which lookup was used and how it performed/i);
      expect(t).toMatch(/the figures it returned \(such as a balance or a category total\)/i);
      expect(t).toMatch(/That log does not contain the text of your conversation/i);
    });

    it('says statement files are encrypted by Fynora, including while waiting for import, and other data is not', () => {
      const t = policyText();
      expect(t).not.toMatch(/statements, budgets, goals\) is protected by the security/i);
      expect(t).toMatch(/Fynora encrypts your statement files itself, with AES-256-GCM, before they are stored: the file you\s+upload while it waits for you to review and import it, and the copy kept after you import it/i);
      expect(t).toMatch(/other financial data \(transactions, accounts, budgets, goals\) is protected by the security/i);
    });

    it('drops the old deleted-statement exception and says what deleting a statement removes', () => {
      const t = policyText();
      expect(t).not.toMatch(/not\s+currently erased/i);
      expect(t).not.toMatch(/working to close it/i);
      expect(t).toMatch(/When you delete a statement, Fynora removes at once, from the statement's own record, its file\s+name, the account-holder name it read from the statement/i);
      expect(t).toMatch(/Two other\s+records of the same upload still hold the file's name until you delete your account: its entry\s+in your import history, and the technical record/i);
    });

    // External privacy review, November 2026: the records kept after deletion stay linked to the
    // account's id (AccountPurgeSweepService keeps the user row, with name, email and phone
    // replaced), so they are pseudonymous, and the policy must not call them anonymized.
    it('does not call the records kept after deletion anonymous, and says what they stay linked to', () => {
      const t = policyText();
      expect(t).not.toMatch(/anonymi[sz]ed account/i);
      expect(t).not.toMatch(/anonymi[sz]ed placeholder/i);
      expect(t).toMatch(/stay linked to\s+your account's internal ID/i);
      expect(t).toMatch(/the ID itself is not anonymous/i);
    });

    it('says what happens to Ask Fyn conversations while AI is off, and that one cannot be deleted alone', () => {
      const t = policyText();
      expect(t).toMatch(/While AI features are off, nothing from your conversations\s+is sent to the AI/i);
      expect(t).toMatch(/A single conversation cannot yet be\s+deleted on its own/i);
    });

    it('names the staff import-diagnosis AI request and says it holds no customer data', () => {
      const t = policyText();
      expect(t).toMatch(/Fynora's staff can also ask an AI service to explain why an import failed, and that\s+request holds only Fynora's own status codes, never your data/i);
    });

    it('says a wrongly classified person can be recorded, so the shared record is not anonymous', () => {
      const t = policyText();
      expect(t).toMatch(/If a\s+person is wrongly classified as a business, their UPI ID can be recorded/i);
      expect(t).toMatch(/the shared record described below is not anonymous/i);
    });

    it('opens with a table of each purpose, its data, and whether it is optional', () => {
      render(<MemoryRouter><Privacy /></MemoryRouter>);
      const table = document.querySelector('#purposes table');
      expect(table).not.toBeNull();
      const rows = Array.from(table!.querySelectorAll('tbody tr')).map((r) => r.textContent ?? '');
      expect(rows).toHaveLength(9);
      expect(rows.find((r) => r.startsWith('AI features'))).toMatch(/Optional: off until you turn it on/);
      expect(rows.find((r) => r.startsWith('Offers and news'))).toMatch(/Optional: off until you turn it on/);
    });

    it('covers deleting a Google or Apple account, Firebase, account names and store subscriptions', () => {
      const t = policyText();
      expect(t).toMatch(/whether you sign in with a password, Google or Apple/i);
      expect(t).toMatch(/The names you gave your accounts, and the account details Fynora read from\s+your statements, are removed too/i);
      expect(t).toMatch(/Your phone number is also deleted from Firebase Authentication/i);
      expect(t).toMatch(/It cannot cancel one\s+bought through the App Store or Google Play/i);
      expect(t).toMatch(/The deletion screen reminds you if you have one/i);
    });

    it('lists what is kept after account deletion, including the acceptance record', () => {
      expect(policyText()).toMatch(/What we keep after you delete your account: a record of each version of the Terms of Service and\s+this policy you agreed to/i);
    });

    it('says how long an upload that is never imported is kept', () => {
      expect(policyText().replace(/\s+/g, ' ')).toContain(
        'If you upload a statement and never import it, the copy waiting for your review is deleted 48 hours '
          + 'after you upload it (or, if we hold it for our own review first, 48 hours after that review ends), and '
          + 'the uploaded file itself is deleted from our storage 90 days after we finish reading it.');
    });

    it('adds the acceptance record to the export, the age confirmation, and re-asking on a material change', () => {
      const t = policyText();
      expect(t).toMatch(/the record of each version of the Terms of Service and this policy you\s+agreed to, the record of each time you turned a notification switch on or off, plus the original statement files/i);
      expect(t).toMatch(/you confirm that you are 18 or older/i);
      expect(t).toMatch(/ask you to read and agree to the new version before you continue/i);
    });
  });

  // V305/V306: promotional push is opt-in, and every change to the switch is recorded. Each line
  // here is a claim the code backs: AudienceSql (opt-in only), NotificationPreferenceService (push
  // only, history), NotificationDispatcher (re-checked at send), DataExportService and the purge.
  it('describes "Offers and news": opt-in push only, how to withdraw, and the record kept of each change', () => {
    const t = policyText();
    // Not just "in the app": store builds released before this change have no such switch.
    expect(t).toMatch(/You get them only if you turn on "Offers and news" in Settings, under Notifications,\s+on the website or in an up-to-date version of the app/);
    expect(t).toMatch(/It is off unless you turn it on, and leaving it off changes nothing else/);
    expect(t).toMatch(/Fynora does not send offers by\s+email or text message/);
    expect(t).toMatch(/device token of each phone you use the\s+Fynora app on/);
    expect(t).toMatch(/A notification that has not been\s+sent yet is not sent once you do, even if it was already queued/);
    expect(t).toMatch(/records when, from which app, and which wording of the switch the app showed/);
    expect(t).toMatch(/consent to "Offers and news" push\s+notifications \(Settings, under Notifications\)/);
    expect(t).toMatch(/Only if you turn on "Offers and news", it is also\s+used to send you push notifications/);
    expect(t).toMatch(/notification\s+switch such as "Offers and news" on or off/);
  });

  it('discloses the sign-up source: the referring site and the first page landed on by address only, the four utm tags, and deletion with the account', () => {
    const t = policyText();
    expect(t).toMatch(/When you sign up on the website, Fynora also notes where that visit came from/i);
    expect(t).toMatch(/its address only, never the page you were on/i);
    expect(t).toMatch(/utm_source, utm_medium, utm_campaign, utm_content/);
    expect(t).toMatch(/which public\s+page of Fynora you first landed on \(its address only, such as \/help\)/i);
    expect(t).toMatch(/kept with your account and deleted with\s+it/i);
  });

});
