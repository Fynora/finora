import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { api, rawApi, type ApiEnvelope } from './client';
import { encodeBase64 } from '../lib/base64';
import { decodeUtf8 } from '../lib/utf8';
import { isCanceled, isOffline } from '../lib/apiError';
import { shareFileAndCleanUp } from '../lib/shareFile';
import type {
  Account, AccountStatementGroup, Budget, CounterpartyGroup, DashboardSummary, DetectedAccountInfo,
  Goal, ImportSummary, MerchantGroup, ReimportResult, StagedAccountSection, StagedRow,
  StatementSummary, Transaction, TransactionExplanation, TransactionSource, VerificationReport,
  WorkspaceSettings, UnparseableRow,
} from '../types';

// Ported from frontend/src/api/endpoints.ts -- these are plain axios calls with TS types, no DOM
// dependency, so almost everything here is unchanged from the web app. The two exceptions are
// import file upload (web's `File`/FormData vs. RN's `{uri,name,type}` FormData shape) and
// statement file download (web's Blob+<a> click has no native equivalent) -- both marked below
// and left for Phase 3 (Import Flow), which is where the real file-picker/upload/download work
// happens. Keep this file in sync with the web version by hand for every other endpoint.

/**
 * Re-reads an ArrayBuffer-typed error response as the JSON envelope it actually is, so the
 * message survives.
 *
 * Mobile's counterpart to the web app's `withBlobErrorMessage`: any request sent with
 * responseType: 'arraybuffer' gets that response type applied to error responses too. The
 * backend's error body is a normal ApiResponse envelope, but axios hands it over as a raw
 * ArrayBuffer, so every consumer that looks for `.data.message` -- including client.ts's own
 * interceptor -- finds nothing and the actionable text is discarded. Mutates the error in place
 * so the shape callers already expect (`err.response.data.message`) is what they get.
 */
async function withArrayBufferErrorMessage(err: unknown): Promise<unknown> {
  const response = (err as { response?: { data?: unknown } })?.response;
  if (!(response?.data instanceof ArrayBuffer)) return err;
  try {
    const parsed = JSON.parse(decodeUtf8(response.data));
    response.data = { message: parsed?.message, errorCode: parsed?.errorCode };
  } catch {
    // Not JSON (a proxy's HTML error page, a truncated body). Leave a usable message rather than
    // an unreadable ArrayBuffer, which is what the caller had before this existed.
    response.data = { message: 'The download failed and the server did not explain why.' };
  }
  return err;
}

export interface AuthResponseDto {
  token: string;
  refreshToken: string;
  email: string;
  fullName: string;
  phoneVerified: boolean;
  maskedPhone: string | null;
  /** The real Fynora user id -- subscription billing V4 needs it to call RevenueCat's
   *  Purchases.configure({ appUserID }) with the real, authenticated id at sign-in. */
  id: string;
  // Same channel phoneVerified already rides -- see docs/superpowers/specs/
  // 2026-09-06-first-login-onboarding-tour-design.md §7.
  onboardingCompleted: boolean;
}

export const authApi = {
  // referralCode: optional -- set only when the user typed one into RegisterScreen's own
  // "Referral code (optional)" field. Mirrors the backend's RegisterRequest.referralCode exactly;
  // an unrecognized or mistyped code is a silent no-op server-side, never a rejected signup.
  register: (email: string, password: string, fullName: string, phoneNumber: string, referralCode?: string) =>
    api.post<AuthResponseDto>('/auth/register', { email, password, fullName, phoneNumber, referralCode }),
  login: (identifier: string, password: string) =>
    api.post<AuthResponseDto>('/auth/login', { identifier, password }),
  // Identifier-first entry step (Phase 3B) -- resolves an email or mobile number to what the
  // client should show next, without a raw exists boolean. See frontend/src/api/endpoints.ts's
  // own copy: nextAction is 'EXISTS' for an existing account (Phase 7, resolved 2026-08-23: no
  // longer distinguishes which sign-in method it uses), or 'CONTINUE' when there isn't one yet.
  identify: (identifier: string) =>
    api.post<{ nextAction: string }>('/auth/identify', { identifier }).then((r) => r.data),
  // D-23 Phase 2. idToken is the raw credential from @react-native-google-signin/google-signin --
  // verified server-side (GoogleIdTokenVerifierService), never trusted client-side. Same endpoint
  // web's GoogleSignInButton already calls; see frontend/src/api/endpoints.ts's own copy.
  google: (idToken: string) => api.post<AuthResponseDto>('/auth/google', { idToken }),
  // D-23 Phase 2 / D-26 (iOS only). idToken is the raw credential from
  // expo-apple-authentication's signInAsync(). fullName is optional and NOT part of the token --
  // Apple hands it to the CLIENT, not the backend, and only on the user's very first
  // authorization for this app -- see AppleAuthRequest's own doc comment on the backend.
  apple: (idToken: string, fullName?: string) =>
    api.post<AuthResponseDto>('/auth/apple', { idToken, fullName }),
  // Completes the "Welcome back — reactivate your account?" prompt LoginScreen shows after a
  // deactivated account's password checks out -- see AuthContext.reactivate. Returns the same
  // shape as login.
  reactivate: (token: string) =>
    api.post<AuthResponseDto>('/auth/reactivate', { token }),
  forgotPassword: (email: string) =>
    api.post<{ message: string; devResetLink: string | null }>('/auth/forgot-password', { email }).then((r) => r.data),
  // BH-015 fix. Not called from any screen yet -- password-reset completion is web-only on
  // mobile today (see ForgotPasswordScreen's own doc comment) -- kept here, signature-matched to
  // the backend contract, for whenever an in-app completion screen is built.
  verifyResetPasswordPhone: (token: string, phoneNumber: string) =>
    api.post<{ message: string }>('/auth/reset-password/phone', { token, phoneNumber }).then((r) => r.data),
  resetPassword: (token: string, firebaseIdToken: string, newPassword: string) =>
    api.post<{ message: string }>('/auth/reset-password', { token, firebaseIdToken, newPassword }).then((r) => r.data),
  // Uses the bare rawApi instance (not `api`) so a failing/expiring access token can't interfere
  // with the refresh call itself.
  refresh: (refreshToken: string) =>
    rawApi.post<ApiEnvelope<{ token: string; refreshToken: string }>>('/auth/refresh', { refreshToken }).then((r) => r.data.data),
  logout: (refreshToken: string) =>
    api.post<{ message: string }>('/auth/logout', { refreshToken }).then((r) => r.data),
};

export const phoneApi = {
  verify: (firebaseIdToken: string) =>
    api.post<{ message: string }>('/phone/verify', { firebaseIdToken }).then((r) => r.data),
};

export interface AccountRequest {
  name: string;
  accountType: string;
  balance?: number;
  creditLimit?: number;
  dueDate?: string;
  investmentKind?: string;
  accountHolderName?: string;
  accountNumberMasked?: string;
  bankId?: string;
  branchName?: string;
  ifscCode?: string;
}

export const accountsApi = {
  list: () => api.get<Account[]>('/accounts').then((r) => r.data),
  create: (body: AccountRequest) => api.post<Account>('/accounts', body).then((r) => r.data),
  update: (id: string, body: AccountRequest) => api.put<Account>(`/accounts/${id}`, body).then((r) => r.data),
  remove: (id: string) => api.delete(`/accounts/${id}`),
};

export interface TransactionFilters {
  accountId?: string;
  categoryId?: string;
  type?: string;
  // Phase 4 (Medium-Tier Parity). Ledger's own Status column (reconciliationBadge) already
  // surfaces exactly these values -- see Transaction.ReconciliationStatus for the full set. The
  // backend has accepted this param since before this session (TransactionController.search's own
  // doc comment names Ledger's Status column filter as the reason it exists); no client used it.
  status?: string;
  dateFrom?: string;
  dateTo?: string;
  amountMin?: number;
  amountMax?: number;
  keyword?: string;
  page?: number;
  size?: number;
  sortField?: string;
  sortDir?: string;
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UpdateTransactionPayload {
  date?: string | null;
  description?: string | null;
  merchant?: string | null;
  amount?: number | null;
  type?: 'INCOME' | 'EXPENSE' | null;
  categoryName?: string | null;
  notes?: string | null;
  tags?: string[] | null;
}

// Mirrors frontend/src/api/endpoints.ts's identical interface -- accountId is required (a
// transaction always belongs to an account the caller owns), unlike UpdateTransactionPayload
// above, which deliberately excludes it. merchant is derived from description server-side and
// never sent; a null/omitted categoryName takes the engine's own auto-categorization path.
export interface CreateTransactionPayload {
  accountId: string;
  categoryName?: string | null;
  date: string;
  description: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  tags?: string[];
  // Identifies one logical create ATTEMPT so a double-tap or a retried request cannot post the
  // same transaction twice, or move the account balance twice. See lib/idempotencyKey.ts.
  idempotencyKey?: string;
}

export const transactionsApi = {
  search: (filters: TransactionFilters) =>
    api.get<PagedResponse<Transaction>>('/transactions', { params: filters }).then((r) => r.data),
  needsReview: () => api.get<Transaction[]>('/transactions/needs-review').then((r) => r.data),
  // The bulk half of the review backlog. Disjoint from needsReview() above -- the server removes
  // anything it returns here from that list, so the two are rendered together, not as alternatives.
  needsReviewGroups: () =>
    api.get<MerchantGroup[]>('/transactions/groups/needs-review').then((r) => r.data),
  // Phase 4. Disjoint from BOTH needsReview() and needsReviewGroups() above -- a merchant-matched
  // row never reaches this grouping (TransactionGroupingService's own doc), so the three partition
  // the backlog rather than double-surfacing a row under two different headers.
  needsReviewByCounterparty: () =>
    api.get<CounterpartyGroup[]>('/transactions/groups/needs-review/by-counterparty').then((r) => r.data),
  create: (body: CreateTransactionPayload) => api.post<Transaction>('/transactions', body).then((r) => r.data),
  update: (id: string, body: UpdateTransactionPayload) =>
    api.put<Transaction>(`/transactions/${id}`, body).then((r) => r.data),
  updateCategory: (id: string, category: string) =>
    api.patch<Transaction>(`/transactions/${id}/category`, { category }).then((r) => r.data),
  remove: (id: string) => api.delete(`/transactions/${id}`),
  // { ids } rather than a bare array: the endpoint now takes a validated DTO that bounds the
  // list (MAX_BULK_IDS). It previously accepted an unbounded List<UUID> straight off the body.
  bulkDelete: (ids: string[]) => api.post('/transactions/bulk-delete', { ids }),
  bulkRecategorize: (ids: string[], category: string) =>
    api.post('/transactions/bulk-category', { ids, category }),
  // BH-027: "no, these really are two separate transactions." Records a human ruling that
  // outranks the reconciliation engine's own guess -- see TransactionService.confirmNotDuplicate.
  // Mirrors frontend/src/api/endpoints.ts.
  confirmNotDuplicate: (id: string) =>
    api.post<Transaction>(`/transactions/${id}/not-duplicate`).then((r) => r.data),
  // "Where did this number come from?" (Track C/C7) — fetched on demand from the source panel,
  // never on every row of the Ledger's list.
  source: (id: string) => api.get<TransactionSource>(`/transactions/${id}/source`).then((r) => r.data),
  // "Why this category?" (Phase 4) — same on-demand contract as source() above.
  explanation: (id: string) => api.get<TransactionExplanation>(`/transactions/${id}/explanation`).then((r) => r.data),
};

/**
 * Mirrors the backend's ImportDto.ConfirmedRow exactly.
 *
 * Declared against the backend record rather than copied from the web app's own interface, which
 * omits the last two: the web code passes them anyway (excess-property checking doesn't apply once
 * an object literal has been through a variable), so its type understates what it sends. Both are
 * carried straight through from staging and must survive review unchanged, or the ledger loses the
 * statement's reference numbers and running balances.
 */
export interface ConfirmedRowPayload {
  date: string;
  description: string;
  amount: number;
  type: 'INCOME' | 'EXPENSE';
  category: string;
  include: boolean;
  categorySource: string;
  ruleId: string | null;
  likelyDuplicate: boolean;
  referenceNumber: string | null;
  balanceAfter: number | null;
  /** Echoed from StagedRow.rowPosition unchanged -- see that field's own doc comment. */
  rowPosition: number | null;
  /** Echoed from StagedRow.categoryConfidence unchanged -- see that field's own doc comment. Lands
   *  on Transaction.decisionConfidence at confirm time. */
  categoryConfidence: number | null;
  /**
   * The user's ANSWER on the duplicate review screen, as opposed to `likelyDuplicate`, which is the
   * engine's GUESS. True only when the engine flagged the row and the person chose "Import anyway".
   *
   * Optional because the backend defaults it to false, and its doc comment names this app as the
   * client that does not send it. That is no longer true, and the field matters more here than the
   * default suggests: without it, reconciliation re-flags the row the moment it lands and strips it
   * from every spend total, so the user's decision shows in the ledger and vanishes from the
   * numbers. See V65 for the measured damage on the web path.
   */
  confirmedNotDuplicate?: boolean;
}

/**
 * Mirrors the backend's ImportDto.NewAccountRequest.
 *
 * Everything from `detectedProduct` down is echoed back unchanged from what staging detected --
 * the review screen displays these read-only, so there is nothing here for a client to have gotten
 * wrong. Dropping them is silent and expensive: a fixed deposit becomes an empty savings account,
 * and without `productIdentityHash` a re-import cannot tell "the deposit I already hold" from a new
 * one, so it double-counts in net worth.
 *
 * `productIdentityHash` is already a hash by the time the client sees it -- no unmasked account
 * number ever leaves the server -- so echoing it back discloses nothing.
 */
export interface NewAccountPayload {
  name: string;
  accountType: 'SAVINGS' | 'CREDIT_CARD' | 'WALLET' | 'INVESTMENT';
  openingBalance: number | null;
  creditLimit: number | null;
  dueDate: string | null;
  accountHolderName?: string | null;
  accountNumberMasked?: string | null;
  bankId?: string | null;
  branchName?: string | null;
  ifscCode?: string | null;
  detectedProduct?: string | null;
  productIdentityHash?: string | null;
  principalAmount?: number | null;
  interestRate?: number | null;
  maturityDate?: string | null;
  maturityAmount?: number | null;
  installmentAmount?: number | null;
  installmentsPaid?: number | null;
  installmentsTotal?: number | null;
}

export interface ConfirmPayload {
  sessionId: string;
  rows: ConfirmedRowPayload[];
  existingAccountId: string | null;
  newAccount: NewAccountPayload | null;
  statementOpeningBalance: number | null;
  statementClosingBalance: number | null;
  // Echoed back from DetectedAccountInfo.statementPeriodStart/End -- see ConfirmRequest's own doc
  // comment on the backend (frontend/src/api/endpoints.ts already sends these; this client never
  // did). ImportService persists it verbatim onto StatementImport (read by Statement History and
  // the "View in Ledger" period filter) and gates the PNB-boundary-date opening-balance
  // carry-forward fix on it being non-null -- omitting it silently disabled both for a mobile
  // confirm. It plays no part in the Free-tier statement-period cap, which is decided from the
  // session's own server-staged detectedAccount, never this echoed field -- see
  // requireStatementPeriodWithinFreeLimit's own doc comment on the backend.
  statementPeriodStart: string | null;
  statementPeriodEnd: string | null;
  // Only meaningful to confirmReimport, for a statement whose stored bytes are a password-protected
  // PDF -- see ConfirmRequest's own doc comment on the backend. Every other confirm path ignores it.
  password?: string;
  // Also reimport-only (Track B/B1). Identifies one logical confirm ATTEMPT so the server can
  // refuse a replay of it -- a first-time import needs no key, since its ImportSession is claimed
  // atomically server-side and cannot be confirmed twice. See lib/idempotencyKey.ts.
  idempotencyKey?: string;
  // Phase 4 (Medium-Tier Parity). docs/proposals/account-ownership-intelligence-proposal.md §3.1.
  // Set true only after the user has explicitly clicked past a holder-name mismatch warning (see
  // lib/holderNameMatcher.ts's own doc comment on why this client-side check never blocks on its
  // own) -- omitted (not false) otherwise, matching ConfirmRequest's own optional-field contract
  // on the backend.
  userConfirmedContinue?: boolean;
}

interface SectionConfirmPayload {
  rows: ConfirmedRowPayload[];
  existingAccountId: string | null;
  newAccount: NewAccountPayload | null;
  statementOpeningBalance: number | null;
  statementClosingBalance: number | null;
  // See ConfirmPayload's identical field for the full reasoning. Carried here too since
  // SectionConfirm on the backend accepts it per section -- unused by any mobile call site today
  // (confirmMulti has none; mobile discards a multi-account result instead), but the type stays
  // complete rather than silently narrower than the backend contract it mirrors.
  userConfirmedContinue?: boolean;
}

export interface MultiAccountConfirmPayload {
  sessionId: string;
  sections: SectionConfirmPayload[];
}

export interface ImportSessionSummary {
  id: string;
  fileName: string;
  rowCount: number;
  createdAt: string;
  expiresAt: string;
}

export interface StagingResult {
  rows: StagedRow[];
  totalParsed: number;
  flaggedDuplicates: number;
  detectedAccount: DetectedAccountInfo;
  unparseableRows: UnparseableRow[];
  // Phase 5 (Low-Priority Polish). Optional rather than required: absent means an older backend
  // that predates verification, which is the same "not checked" state as an explicit null and
  // must not read as a failure. Mirrors frontend/src/api/endpoints.ts's identical field exactly.
  verification?: VerificationReport | null;
}

interface PdfStagingSessionResult {
  sessionId: string;
  multiAccount: boolean;
  staging: StagingResult | null;
  sections: StagedAccountSection[] | null;
}

type ProgressCallback = (percent: number) => void;

// timeout: 0 overrides client.ts's default 30s timeout -- a statement upload over a slow mobile
// connection can legitimately take longer than that, and unlike an ordinary JSON call, this one
// already gives the user live proof it's still working via onUploadProgress. Applies whether or
// not a progress callback was actually passed, since the upload itself is what can be slow.
function toUploadProgressConfig(onProgress?: ProgressCallback, signal?: AbortSignal) {
  return {
    timeout: 0,
    // Why a signal matters MORE here than on an ordinary call: this is the one request with
    // `timeout: 0`, so nothing else will ever end it. Without a way to abort, a stalled upload on a
    // dead connection hangs until the OS tears the socket down, with the progress bar frozen and no
    // way out of the screen.
    ...(signal ? { signal } : {}),
    ...(onProgress
      ? {
          onUploadProgress: (e: { loaded: number; total?: number }) => {
            if (e.total) onProgress(Math.round((e.loaded / e.total) * 100));
          },
        }
      : {}),
  };
}

// React Native's FormData has no web `File` type to append -- it accepts a plain
// {uri, name, type} descriptor instead, which is exactly the shape `expo-document-picker`'s
// result already gives you (Phase 3 wires the real picker up to this).
export interface RNFile {
  uri: string;
  name: string;
  type: string;
}

/**
 * One retry, only for a genuine transport-layer failure (no response reached the client at all --
 * see isOffline()'s own doc comment).
 *
 * The document picker (`pickStatement()` in lib/statementFile.ts) hands control to a separate OS
 * activity and back. Verified against a real device, not assumed: the moment that activity
 * returns, an upload started immediately can fail with axios's ERR_NETWORK even though the file is
 * confirmed present on disk and every other endpoint reached from the same screen moments earlier
 * or later succeeds -- the app's process is briefly resumed before the OS has finished restoring
 * its network callback registration (visible in logcat as a ConnectivityService RemoteException
 * for this app's own request package right after the picker activity exits). A fixed delay before
 * every upload would tax the common case to paper over a one-off timing gap; retrying once, only
 * on the specific error shape this gap produces, costs nothing when the gap isn't there and
 * recovers when it is.
 */
async function stageWithRetry<T>(attempt: () => Promise<T>): Promise<T> {
  try {
    return await attempt();
  } catch (e) {
    // Cancel is checked first and deliberately: a cancelled request has no response and so passes
    // isOffline's test, which meant this retried the very upload the user just cancelled -- the
    // file went up a second time and the cancel appeared to do nothing.
    if (isCanceled(e)) throw e;
    if (!isOffline(e)) throw e;
    return attempt();
  }
}

export const importApi = {
  // No explicit Content-Type header on either upload below: 'multipart/form-data' with no boundary
  // is invalid HTTP (the multipart parser needs one), and axios only computes the correct
  // boundary-included header when nothing has already set Content-Type. This was previously set by
  // hand -- turned out to be a red herring for the real bug below (see stageWithRetry's own doc
  // comment), but wrong regardless of that, since a manual header without a boundary can never be
  // valid multipart.
  stageCsv: (file: RNFile, onProgress?: ProgressCallback, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file as unknown as Blob);
    return stageWithRetry(() =>
      api
        .post<{ sessionId: string; staging: StagingResult }>('/import/csv/stage', form, toUploadProgressConfig(onProgress, signal))
        .then((r) => r.data)
    );
  },
  // `password` opens a protected statement (most Indian banks e-mail them that way). It rides in
  // the form body, never the query string, so it can't reach a server access log. Omitted when
  // blank, and harmless when the file turns out not to need one -- so the caller never has to
  // inspect the file to decide whether to send it.
  stagePdf: (file: RNFile, onProgress?: ProgressCallback, password?: string, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file as unknown as Blob);
    if (password) form.append('password', password);
    return stageWithRetry(() =>
      api
        .post<PdfStagingSessionResult>('/import/pdf/stage', form, toUploadProgressConfig(onProgress, signal))
        .then((r) => r.data)
    );
  },
  confirm: (payload: ConfirmPayload) =>
    api.post<ImportSummary>('/import/csv/confirm', payload).then((r) => r.data),
  confirmMulti: (payload: MultiAccountConfirmPayload) =>
    api.post<{ perAccount: ImportSummary[] }>('/import/pdf/confirm-multi', payload).then((r) => r.data),
  listSessions: () => api.get<ImportSessionSummary[]>('/import/sessions').then((r) => r.data),
  getSession: (id: string) =>
    api.get<{ sessionId: string; staging: StagingResult }>(`/import/sessions/${id}`).then((r) => r.data),
  discardSession: (id: string) => api.delete(`/import/sessions/${id}`),
  // "Your recent failed imports" -- Premium Import Reliability v1, §2.1. A document that never got
  // far enough to become an ImportSession (no header found, zero transactions, a scanned PDF)
  // previously left no trace its owner could see again; this is that trace, read back. Ported
  // alongside importJobsApi below for Phase 4's failed-imports retry section.
  listFailures: () => api.get<ImportFailureSummary[]>('/import/failures').then((r) => r.data),
};

// Premium Import Reliability v1, §2.1 -- mirrors backend ImportDto.ImportFailureSummaryDto exactly,
// including its deliberate omission of failureDetail (admin/debug-only, can carry a fragment of
// the document that defeated the parser). failureCode is a lookup key for
// importFailureMessages.ts, not a message to show verbatim.
export interface ImportFailureSummary {
  reference: string;
  fileName: string;
  failureCode: string | null;
  createdAt: string;
}

/**
 * Phase 4 (Medium-Tier Parity). The asynchronous upload path: hand the file over, watch it, review
 * it when it lands. Runs beside importApi.stageCsv/stagePdf rather than replacing them -- see
 * frontend/src/api/endpoints.ts's identical importJobsApi doc comment for why adding endpoints is
 * non-breaking and both paths reach the same review screen.
 */
export interface ImportJobProgress {
  jobId: string;
  fileName: string;
  status: 'QUEUED' | 'PARSING' | 'ANALYZING' | 'DEDUPING' | 'IMPORTING' | 'LEARNING'
    | 'COMPLETED' | 'FAILED' | 'HELD_FOR_REVIEW' | 'HELD_FOR_TRUST_REVIEW' | 'CANCELLED';
  userStatus: 'PROCESSING' | 'COMPLETED' | 'ACTION_REQUIRED' | 'FAILED'
    | 'HELD_FOR_REVIEW' | 'CANCELLED';
  rowsTotal: number | null;
  rowsProcessed: number;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  importSessionId: string | null;
  error: string | null;
  correlationId: string | null;
}

/** One stage's transition, for the import timeline (Premium Import Reliability v1, §3.1). */
export interface ImportTimelineStage {
  stage: ImportJobProgress['status'];
  attempt: number;
  outcome: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED';
  startedAt: string | null;
  endedAt: string | null;
  durationMs: number | null;
}

/** The full timeline for one job. `failureCode` is the wire code (e.g. "IMPORT_001") -- the same
 *  vocabulary importFailureMessage already turns into a curated sentence. */
export interface ImportJobTimeline {
  jobId: string;
  status: ImportJobProgress['status'];
  userStatus: ImportJobProgress['userStatus'];
  failureCode: string | null;
  stages: ImportTimelineStage[];
}

export const importJobsApi = {
  availability: () =>
    api.get<{ asyncImportAvailable: boolean }>('/import/jobs/availability').then((r) => r.data),
  submit: (file: RNFile, onProgress?: ProgressCallback, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file as unknown as Blob);
    return api
      .post<{ jobId: string; statusUrl: string }>('/import/jobs', form, toUploadProgressConfig(onProgress, signal))
      .then((r) => r.data);
  },
  progress: (jobId: string) =>
    api.get<ImportJobProgress>(`/import/jobs/${jobId}`).then((r) => r.data),
  timeline: (jobId: string) =>
    api.get<ImportJobTimeline>(`/import/jobs/${jobId}/timeline`).then((r) => r.data),
  // POST, not DELETE: this ends the work and keeps the row, because a cancelled import is part of
  // the user's history. Returns the job's new state so the caller renders from the response
  // instead of racing its own next poll.
  cancel: (jobId: string) =>
    api.post<ImportJobProgress>(`/import/jobs/${jobId}/cancel`).then((r) => r.data),
};

export const statementImportsApi = {
  listGroupedByAccount: () => api.get<AccountStatementGroup[]>('/statement-imports').then((r) => r.data),
  detail: (id: string) => api.get<StatementSummary>(`/statement-imports/${id}`).then((r) => r.data),
  transactions: (id: string) => api.get<Transaction[]>(`/statement-imports/${id}/transactions`).then((r) => r.data),
  /**
   * "Download" means something different here than on web. The web app streams the file into a
   * Blob and clicks a synthetic <a download>, neither of which exists on native -- and a file
   * dropped into an app's sandbox is invisible to the user anyway. So this writes the bytes into
   * the app's cache directory and hands the URI to the native share sheet, which is where "save to
   * Files", "mail it to myself" and every other real destination live.
   *
   * Cache rather than documents: the OS may reclaim it, which is correct for a scratch copy the
   * user has already been given a chance to put somewhere permanent. Nothing here re-downloads on
   * its own, so a reclaimed file costs one more tap, not data loss.
   *
   * arraybuffer -> base64 because expo-file-system's write() takes a string; axios on React Native
   * has no Blob to hand over.
   */
  downloadFile: async (id: string, fileName: string) => {
    if (!(await Sharing.isAvailableAsync())) {
      throw new Error('Sharing is not available on this device.');
    }
    let res;
    try {
      res = await api.get<ArrayBuffer>(`/statement-imports/${id}/file`, { responseType: 'arraybuffer' });
    } catch (err) {
      // responseType: 'arraybuffer' applies to ERROR responses too, so on a 4xx/5xx error.response.data
      // is an ArrayBuffer rather than the parsed {message, errorCode} envelope. client.ts's interceptor
      // tests error.response?.data?.message, an ArrayBuffer has no such property, the normalising
      // branch is skipped, and the caller gets an error with no readable detail -- for a path whose
      // backend failures are specific and actionable ("Statement ... is in object storage, but no
      // storage provider is configured"). Decoding the buffer back to text restores the envelope the
      // rest of the app expects.
      throw await withArrayBufferErrorMessage(err);
    }
    const file = new File(Paths.cache, fileName);
    // A previous share of the same statement leaves the file behind; write() will not overwrite.
    if (file.exists) file.delete();
    file.create();
    file.write(encodeBase64(res.data), { encoding: 'base64' });
    await shareFileAndCleanUp(file, {
      mimeType: fileName.toLowerCase().endsWith('.pdf') ? 'application/pdf' : 'text/csv',
      UTI: fileName.toLowerCase().endsWith('.pdf') ? 'com.adobe.pdf' : 'public.comma-separated-values-text',
      dialogTitle: fileName,
    });
  },
  // `password` is only ever needed for a statement originally uploaded as a protected PDF: the
  // stored bytes are still encrypted, and the password used at upload is deliberately never
  // persisted, so it has to be supplied again here. In the body, never the URL -- a document
  // password in a query string is captured by access logs and proxy logs.
  reimport: (id: string, password?: string) =>
    api.post<ReimportResult>(`/statement-imports/${id}/reimport`, password ? { password } : {}).then((r) => r.data),
  confirmReimport: (id: string, payload: Omit<ConfirmPayload, 'newAccount' | 'sessionId'>) =>
    api.post<ImportSummary>(`/statement-imports/${id}/reimport/confirm`, { ...payload, newAccount: null }).then((r) => r.data),
  remove: (id: string) => api.delete(`/statement-imports/${id}`),
};

export const budgetsApi = {
  list: () => api.get<Budget[]>('/budgets').then((r) => r.data),
  upsert: (categoryName: string, monthlyLimit: number) =>
    api.put<Budget>('/budgets', { categoryName, monthlyLimit }).then((r) => r.data),
};

export const goalsApi = {
  list: () => api.get<Goal[]>('/goals').then((r) => r.data),
  create: (body: Partial<Goal>) => api.post<Goal>('/goals', body).then((r) => r.data),
  addContribution: (id: string, amount: number) =>
    api.post<Goal>(`/goals/${id}/contributions`, { amount }).then((r) => r.data),
  remove: (id: string) => api.delete(`/goals/${id}`),
};

export interface OnboardingStatus {
  onboardingCompleted: boolean;
  financialFocus: string[];
}

export interface ChecklistItem {
  key: string;
  completed: boolean;
}

export interface ChecklistStatus {
  items: ChecklistItem[];
  completedCount: number;
  totalCount: number;
}

export const onboardingApi = {
  status: () => api.get<OnboardingStatus>('/onboarding/status').then((r) => r.data),
  setFinancialFocus: (focusKeys: string[]) =>
    api.post<OnboardingStatus>('/onboarding/financial-focus', { focusKeys }).then((r) => r.data),
  complete: () => api.post<void>('/onboarding/complete', {}),
  reset: () => api.post<void>('/onboarding/reset', {}),
  getChecklist: () => api.get<ChecklistStatus>('/onboarding/checklist').then((r) => r.data),
  completeChecklistItem: (itemKey: string) =>
    api.post<void>(`/onboarding/checklist/${itemKey}/complete`, {}),
};

export interface CategoryOption {
  id: string;
  name: string;
  isSystem: boolean;
  icon: string;
  color: string;
}
// icon.label is a human-readable name ("Groceries") for an icon TOKEN ("shopping-cart") --
// looked up through categoryIcons.ts's ICON_COMPONENTS map, mirroring web's identical split.
// color.label, unlike icon.label, is already a ready-to-use hex string (CategoryPalette.COLORS'
// own values) -- usable directly as a backgroundColor, same as web's CategoryCreateEditPanel.
export interface CategoryOptions {
  icons: { token: string; label: string }[];
  colors: { token: string; label: string }[];
}
export interface CategoryUsage {
  transactionCount: number;
  hasBudget: boolean;
  ruleCount: number;
  learningRowCount: number;
}
export const categoriesApi = {
  list: () => api.get<CategoryOption[]>('/categories').then((r) => r.data),
  options: () => api.get<CategoryOptions>('/categories/options').then((r) => r.data),
  create: (name: string, icon: string, color: string) =>
    api.post<CategoryOption>('/categories', { name, icon, color }).then((r) => r.data),
  update: (id: string, changes: { name?: string; icon?: string; color?: string }) =>
    api.patch<CategoryOption>(`/categories/${id}`, changes).then((r) => r.data),
  delete: (id: string, reassignTo?: string) =>
    api.delete(`/categories/${id}`, { params: reassignTo ? { reassignTo } : undefined }),
  usage: (id: string) => api.get<CategoryUsage>(`/categories/${id}/usage`).then((r) => r.data),
};

// --- Gmail Transaction Sync (mobile Phase 3) ---
//
// Mirrors frontend/src/api/endpoints.ts's identical interfaces/gmailApi exactly, connect() aside
// -- that one sends `platform: 'MOBILE'` so the backend's callback redirects back into this app via
// a finora:// deep link instead of the web settings URL. See ReturnPlatform's own doc comment
// (backend/src/main/java/com/finora/integrations/google/ReturnPlatform.java) for why that's a
// closed value resolved server-side, not a URL this client supplies.

export interface GmailConnectionStatus {
  connected: boolean;
  status: string | null;
  needsReconnect: boolean;
  googleEmail: string | null;
  grantedScopes: string[];
  connectedAt: string | null;
  lastSyncedAt: string | null;
  lastDiscoveryAt: string | null;
  transactionsFound: number;
  needsReview: number;
  available: boolean;
}

// Mirrors GmailReviewItemDto. sessionId is what approve()/reject() take -- there is no separate
// "receipt id"; a Gmail-sourced ImportSession IS the receipt (GmailStagingBridge stages exactly
// one row per session), see GmailReviewService's own doc comment.
export interface GmailReviewItem {
  sessionId: string;
  merchant: string;
  merchantDomain: string;
  amount: number;
  date: string;
  category: string;
  confidence: number | null;
  stagedAt: string;
  reasoning: string;
}

export const gmailApi = {
  status: () => api.get<GmailConnectionStatus>('/integrations/google/gmail/status').then((r) => r.data),
  connect: () =>
    api.post<{ authorizationUrl: string }>('/integrations/google/gmail/connect', null, {
      params: { platform: 'MOBILE' },
    }).then((r) => r.data),
  disconnect: () => api.delete('/integrations/google/gmail/connection'),
  syncNow: () => api.post('/integrations/google/gmail/sync-now'),
  reviewQueue: () =>
    api.get<GmailReviewItem[]>('/integrations/google/gmail/review-queue').then((r) => r.data),
  approve: (sessionId: string, category?: string) =>
    api.post(`/integrations/google/gmail/review/${sessionId}/approve`, category ? { category } : {}),
  reject: (sessionId: string) => api.post(`/integrations/google/gmail/review/${sessionId}/reject`),
};

export const dashboardApi = {
  summary: () => api.get<DashboardSummary>('/dashboard/summary').then((r) => r.data),
};

interface NetWorthSnapshotPoint {
  date: string;
  netWorth: number;
}
export interface NetWorthData {
  totalAssets: number;
  totalLiabilities: number;
  netWorth: number;
  history: NetWorthSnapshotPoint[];
}
export const networthApi = {
  current: () => api.get<NetWorthData>('/networth').then((r) => r.data),
  saveSnapshot: () => api.post<NetWorthData>('/networth/snapshot').then((r) => r.data),
};

interface CategoryMover {
  category: string;
  current: number;
  priorAverage: number;
  pctChange: number | null;
}
/**
 * Track C/C2. Populated only when the current reporting month intersects a known coverage gap on
 * any of the user's live accounts -- see InsightsService.coverageGapsAcross on the backend. Mirrors
 * InsightsDto.CoverageCaveat exactly; this endpoint has always returned it (it just went unread
 * until now), so no backend change was needed to add this field.
 */
export interface CoverageCaveat {
  month: string;
  gaps: { gapStart: string; gapEnd: string }[];
}
export interface InsightsData {
  sentences: string[];
  movers: CategoryMover[];
  coverageCaveat: CoverageCaveat | null;
}
export interface RecurringItem {
  merchant: string;
  label: string;
  averageAmount: number;
  occurrences: number;
  lastDate: string;
  nextEstimate: string;
}
export const recurringApi = {
  list: () => api.get<RecurringItem[]>('/recurring').then((r) => r.data),
};

export const insightsApi = {
  get: () => api.get<InsightsData>('/insights').then((r) => r.data),
};

export interface ReportData {
  month: string;
  income: number;
  expense: number;
  categories: { category: string; amount: number }[];
}
export const reportsApi = {
  availableMonths: () => api.get<string[]>('/reports/months').then((r) => r.data),
  forMonth: (month: string) => api.get<ReportData>('/reports', { params: { month } }).then((r) => r.data),
};

export interface UserSettings {
  email: string;
  fullName: string;
  lowBalanceThreshold: number;
  theme: string;
  timezone: string;
  phoneNumber: string;
  phoneVerified: boolean;
  createdAt: string;
  passwordChangedAt: string | null;
  // 'PASSWORD', 'GOOGLE', or 'APPLE' -- see the backend User.signInMethod's own doc comment
  // (isGoogleAccount()/isAppleAccount()). Deliberately a 3-way union, unlike
  // frontend/src/api/endpoints.ts's identical field (web's is 'PASSWORD' | 'GOOGLE' only, which
  // undercounts an account created via Apple Sign-In -- not fixed here since it's a pre-existing
  // web gap, out of scope for this mobile-only change). Was missing from this file entirely
  // (mobile's ChangeEmailSheet/ChangePasswordSheet only ever offer the password step-up as a
  // result -- see those files' own doc comments); added now so the new account-lifecycle sheets
  // below can gate correctly instead of repeating that gap a third time.
  signInMethod: 'PASSWORD' | 'GOOGLE' | 'APPLE';
}
export const userApi = {
  get: () => api.get<UserSettings>('/users/me').then((r) => r.data),
  update: (body: { lowBalanceThreshold?: number; theme?: string; timezone?: string; fullName?: string }) =>
    api.put<UserSettings>('/users/me', body).then((r) => r.data),
};

export const passwordChangeApi = {
  // Exactly one of currentPassword/googleIdToken/appleIdToken is required -- see
  // PasswordChangeService.start's own GoogleReauthVerifier.verify call, which has accepted all
  // three since before this session; this client only ever sent currentPassword until Phase 4
  // (Medium-Tier Parity) wired the other two through ChangePasswordSheet's own Google/Apple
  // reauth step-up.
  start: (currentPassword: string | null, googleIdToken: string | null, appleIdToken: string | null) =>
    api.post<{ sessionId: string; phoneNumber: string; maskedPhone: string }>(
      '/users/me/password-change/start', { currentPassword, googleIdToken, appleIdToken }
    ).then((r) => r.data),
  verifyOtp: (sessionId: string, firebaseIdToken: string) =>
    api.post<{ message: string }>(
      '/users/me/password-change/verify-otp', { sessionId, firebaseIdToken }
    ).then((r) => r.data),
  complete: (sessionId: string, newPassword: string, signOutOtherDevices: boolean, currentRefreshToken: string) =>
    api.post<{ message: string; otherDevicesSignedOut: boolean }>(
      '/users/me/password-change/complete', { sessionId, newPassword, signOutOtherDevices, currentRefreshToken }
    ).then((r) => r.data),
};

// The self-service account lifecycle -- see UserAccountLifecycleService on the backend and
// frontend/src/api/endpoints.ts's identical accountLifecycleApi. deactivate()/deleteAccount() are
// unchanged from web's shape; exportData() differs because mobile has no Blob/<a> download
// primitive -- same arraybuffer-then-share pattern as statementImportsApi.downloadFile above,
// not the web version's downloadBlob().
export const accountLifecycleApi = {
  // Exactly one of currentPassword/googleIdToken is required -- see passwordChangeApi.start's
  // identical shape and the backend's GoogleReauthVerifier. Both sheets below only ever call this
  // with currentPassword set (Phase 0 scopes the Google/Apple reauth step-up to a later phase --
  // see DeactivateAccountSheet's own doc comment), but the parameter stays nullable to match the
  // backend request shape exactly rather than lying about what it accepts.
  deactivate: (currentPassword: string | null, googleIdToken: string | null, reason: string, note?: string) =>
    api.post<{ message: string }>(
      '/users/me/account/deactivate', { currentPassword, googleIdToken, reason, note }
    ).then((r) => r.data),
  // sessionId proves current-password+OTP -- see PasswordChangeService.consumeForAccountDeletion,
  // reached through the same passwordChangeApi.start/verifyOtp calls DeleteAccountSheet drives
  // (identical to ChangePasswordSheet's own password->otp steps, forking only after verifyOtp).
  deleteAccount: (sessionId: string) =>
    api.post<{ message: string }>('/users/me/account/delete', { sessionId }).then((r) => r.data),
  // Phase C (Download My Data). ArrayBuffer + withArrayBufferErrorMessage, not Blob -- see the
  // comment on that function above for why responseType: 'arraybuffer' needs it. Written into the
  // cache dir and handed to the OS share sheet, since there is no sandboxed "Downloads" location
  // this app can write into directly (same reasoning as statementImportsApi.downloadFile).
  //
  // Deliberately NOT statementImportsApi.downloadFile's encodeBase64(...)+{encoding:'base64'}
  // pattern: that one only ever moves a single bounded statement file (KBs-low MBs). This ZIP
  // bundles every original statement file plus a full data manifest for the account's entire
  // history, open-ended in size -- base64 would hold a second, ~33% LARGER copy of the whole
  // buffer in JS memory simultaneously with the original ArrayBuffer, on top of the buffer axios
  // already has to hold whole (RN's networking layer has no disk-backed Blob/streamed-response
  // equivalent to fetch this into instead). file.write() accepts a Uint8Array directly -- a
  // zero-copy view over the same ArrayBuffer, not a duplicate -- cutting peak memory roughly in
  // half. This does not fully bound the export's memory use (the whole ZIP is still fetched into
  // one in-memory buffer before any of it reaches disk); doing that would need the backend to
  // support a streamable GET download instead of this POST-with-password-in-body shape.
  exportData: async (currentPassword: string | null, googleIdToken: string | null) => {
    if (!(await Sharing.isAvailableAsync())) {
      throw new Error('Sharing is not available on this device.');
    }
    let res;
    try {
      res = await api.post<ArrayBuffer>(
        '/users/me/data-export', { currentPassword, googleIdToken }, { responseType: 'arraybuffer' }
      );
    } catch (err) {
      throw await withArrayBufferErrorMessage(err);
    }
    const fileName = `fynora-data-export-${new Date().toISOString().slice(0, 10)}.zip`;
    const file = new File(Paths.cache, fileName);
    // A previous export attempt can leave the file behind -- write() will not overwrite.
    if (file.exists) file.delete();
    file.create();
    file.write(new Uint8Array(res.data));
    await shareFileAndCleanUp(file, {
      mimeType: 'application/zip',
      UTI: 'com.pkware.zip-archive',
      dialogTitle: fileName,
    });
  },
};

// Phase 4 (docs/proposals/authentication-account-security-review.md). Ported from
// frontend/src/api/endpoints.ts's identical emailChangeApi -- see ChangeEmailModal.tsx's own doc
// comment for why start() is the only call this app's "form" step needs: verify()/complete() run
// from the emailed link (VerifyEmailChangeScreen), not from anything typed in-app.
export const emailChangeApi = {
  start: (currentPassword: string | null, googleIdToken: string | null, appleIdToken: string | null, newEmail: string) =>
    api.post<{ sessionId: string; devVerifyLink: string | null }>(
      '/users/me/email-change/start', { currentPassword, googleIdToken, appleIdToken, newEmail }
    ).then((r) => r.data),
  verify: (sessionId: string, token: string) =>
    api.post<{ message: string }>('/users/me/email-change/verify', { sessionId, token }).then((r) => r.data),
  complete: (sessionId: string) =>
    api.post<{ message: string; email: string }>('/users/me/email-change/complete', { sessionId }).then((r) => r.data),
};

export interface ImportStatistics {
  totalStatements: number;
  totalTransactionsImported: number;
  totalTransactionsSkipped: number;
  lastImportedAt: string | null;
}
// Mirrors backend AnalyticsDto exactly. The five methods below (unlike importStatistics above)
// are gated on the ADVANCED_REPORTS entitlement server-side -- see AnalyticsController's own doc
// comment: the first live call site for EntitlementService#hasEntitlement, enforced per request,
// not just a client-side gate. AdvancedReportsScreen is the first mobile caller for any of them.
export interface TopMerchant { merchantId: string; merchantName: string; totalSpend: number; transactionCount: number; }
export interface TrendPoint { month: string; totalSpend: number; }
export interface CategoryConfidencePoint { category: string; avgConfidence: number; merchantCount: number; }
export interface TopCategory { categoryId: string; categoryName: string; totalSpend: number; transactionCount: number; }
export interface LearningGrowthPoint { month: string; learnedCount: number; correctedCount: number; }

export const analyticsApi = {
  importStatistics: () =>
    api.get<ImportStatistics>('/analytics/merchants', { params: { view: 'importStatistics' } }).then((r) => r.data),
  // month is "YYYY-MM"; omitted means all-time for topMerchants/topCategories, and the trailing
  // window ending this month for trend -- see AnalyticsService's own doc comments.
  topMerchants: (month?: string) =>
    api.get<TopMerchant[]>('/analytics/top-merchants', { params: month ? { month } : {} }).then((r) => r.data),
  trend: () => api.get<TrendPoint[]>('/analytics/trend').then((r) => r.data),
  categoryConfidence: () =>
    api.get<CategoryConfidencePoint[]>('/analytics/category-confidence').then((r) => r.data),
  topCategories: (month?: string) =>
    api.get<TopCategory[]>('/analytics/top-categories', { params: month ? { month } : {} }).then((r) => r.data),
  learningGrowth: () =>
    api.get<LearningGrowthPoint[]>('/analytics/learning-growth').then((r) => r.data),
};

export const workspaceApi = {
  getSettings: () => api.get<WorkspaceSettings>('/workspace/settings').then((r) => r.data),
  updateSettings: (body: { autoApplyConfidenceThreshold: number }) =>
    api.put<WorkspaceSettings>('/workspace/settings', body).then((r) => r.data),
};

// --- Device management (Active Sessions) ---
// GET/DELETE /api/v1/users/me/devices -- backend-complete (DeviceController), no web UI yet
// either. See mobile roadmap Phase 5: recommended as a mobile-first screen.
// Mirrors the backend's DeviceSessionDto exactly. `current`/`sessionExpiresAt` were added to that
// DTO after this type was first written (DeviceSessionDto.from's own doc comment: `current` is
// null-safe -- false whenever the caller's own session id can't be determined, never a guess that
// a session IS the caller's) -- this type simply hadn't been kept in sync, so DeviceSessionsSection
// had no way to badge "this device" or show when the absolute session cap expires, even though the
// backend had been sending both all along.
export interface DeviceSession {
  id: string;
  sessionId: string;
  /** Whether the refresh token making THIS request belongs to this row's session. False (never a
   *  guess) when the caller's session id can't be determined -- see the DTO's own doc comment. */
  current: boolean;
  browser: string | null;
  device: string | null;
  lastSeenIp: string | null;
  lastSeenAt: string;
  createdAt: string;
  expiresAt: string;
  sessionStartedAt: string;
  // Null when the absolute session-length cap is disabled server-side -- render as no expiry, not
  // as a date far in the future. Server-computed specifically so no client does its own clock-skewed
  // arithmetic (see DeviceSessionDto's own doc comment on why this isn't `createdAt + policy`).
  sessionExpiresAt: string | null;
}
export const devicesApi = {
  list: () => api.get<DeviceSession[]>('/users/me/devices').then((r) => r.data),
  revoke: (id: string) => api.delete<{ message: string }>(`/users/me/devices/${id}`).then((r) => r.data),
};

// --- Push notification device tokens (Task 14) ---
// Mirrors backend DeviceTokenController exactly: POST /device-tokens registers this device's FCM
// token, POST /device-tokens/revoke removes it. Revoke is a POST, not a DELETE-with-body -- the
// backend has no precedent for a DELETE carrying a body (some proxies strip it), and the client
// identifies the token to revoke by its own raw string, never a server-side row id it was never
// given. `platform` must be exactly 'ANDROID' or 'IOS' (backend validates
// @Pattern(regexp = "ANDROID|IOS")) -- but it is NOT what routes delivery: iOS devices register an
// FCM token too (via @react-native-firebase/messaging), and FCM relays every send to Apple's APNs
// on this project's behalf (see backend FcmPushProvider's class doc, Ruling O / Task 11). This
// field is retained for diagnostics and per-platform delivery metrics only.
export type DevicePlatform = 'ANDROID' | 'IOS';
export interface RegisteredDeviceToken {
  id: string;
  platform: DevicePlatform;
  registeredAt: string;
}
export const deviceTokensApi = {
  register: (body: { token: string; platform: DevicePlatform }) =>
    api.post<RegisteredDeviceToken>('/device-tokens', body).then((r) => r.data),
  // Backend returns ApiResponse.ok(null, "Device token revoked") -- the response-envelope unwrap
  // (see client.ts) yields the inner `data`, which is null, not a { message } object.
  revoke: (body: { token: string }) =>
    api.post<null>('/device-tokens/revoke', body).then((r) => r.data),
};

// Support, Help & Feedback v1 (Phase 8, mobile). Mirrors frontend/src/api/endpoints.ts's own
// SupportTicketCategory/Status and FeedbackType/Context unions exactly -- see that file's comment
// for why a value added on one side with nothing here to render it is worth guarding against at
// compile time.
export type SupportTicketCategory =
  | 'STATEMENT_IMPORT' | 'CATEGORIZATION' | 'ACCOUNT_LINKING' | 'DATA_ACCURACY' | 'TECHNICAL_ISSUE' | 'OTHER';
export type SupportTicketStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED';
export type ClientPlatform = 'WEB' | 'MOBILE_ANDROID' | 'MOBILE_IOS';

export interface SupportTicketAttachmentSummary {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
}

export interface SupportTicketSummary {
  id: string;
  ticketNumber: string;
  userId: string;
  category: SupportTicketCategory;
  subject: string;
  status: SupportTicketStatus;
  claimedByAdminId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SupportTicketDetail extends SupportTicketSummary {
  description: string;
  source: ClientPlatform;
  appVersion: string | null;
  resolvedAt: string | null;
  closedAt: string | null;
  attachments: SupportTicketAttachmentSummary[];
}

export const supportApi = {
  // No explicit Content-Type header, same reasoning as importApi.stageCsv/stagePdf above: a
  // hand-set 'multipart/form-data' with no boundary is invalid HTTP, and axios only computes the
  // correct boundary when nothing has already set Content-Type. Wrapped in stageWithRetry for the
  // same reason those two are: this follows immediately after a DocumentPicker handoff when an
  // attachment is attached, and is the exact timing gap that helper exists for.
  create: (payload: { category: SupportTicketCategory; subject: string; description: string; file?: RNFile | null }) => {
    const form = new FormData();
    form.append('category', payload.category);
    form.append('subject', payload.subject);
    form.append('description', payload.description);
    if (payload.file) form.append('file', payload.file as unknown as Blob);
    return stageWithRetry(() =>
      api.post<SupportTicketDetail>('/support/tickets', form).then((r) => r.data)
    );
  },
  list: (page = 0, size = 25) =>
    api.get<PagedResponse<SupportTicketSummary>>('/support/tickets', { params: { page, size } }).then((r) => r.data),
  detail: (id: string) => api.get<SupportTicketDetail>(`/support/tickets/${id}`).then((r) => r.data),
  /** Same pattern as statementImportsApi.downloadFile: write the bytes into the cache directory
   *  and hand the URI to the native share sheet -- there is no in-sandbox "download" a user could
   *  otherwise find. */
  downloadAttachment: async (ticketId: string, attachmentId: string, filename: string, contentType: string) => {
    if (!(await Sharing.isAvailableAsync())) {
      throw new Error('Sharing is not available on this device.');
    }
    let res;
    try {
      res = await api.get<ArrayBuffer>(`/support/tickets/${ticketId}/attachments/${attachmentId}`, { responseType: 'arraybuffer' });
    } catch (err) {
      // Same fix as statementImportsApi.downloadFile -- see withArrayBufferErrorMessage's own doc
      // comment above for why responseType: 'arraybuffer' loses the server's real error message.
      throw await withArrayBufferErrorMessage(err);
    }
    const file = new File(Paths.cache, filename);
    if (file.exists) file.delete();
    file.create();
    file.write(encodeBase64(res.data), { encoding: 'base64' });
    await shareFileAndCleanUp(file, { mimeType: contentType, dialogTitle: filename });
  },
};

export type FeedbackType = 'BUG' | 'FEATURE_REQUEST' | 'IMPROVEMENT' | 'GENERAL';
export type FeedbackContext =
  | 'DASHBOARD' | 'TRANSACTIONS' | 'REPORTS' | 'BUDGETS' | 'GOALS' | 'IMPORT_FLOW' | 'ACCOUNTS' | 'SETTINGS' | 'HELP' | 'OTHER';

export interface FeedbackSummary {
  id: string;
  userId: string;
  type: FeedbackType;
  context: FeedbackContext;
  source: ClientPlatform;
  message: string;
  createdAt: string;
}

export const feedbackApi = {
  submit: (payload: { type: FeedbackType; context: FeedbackContext; message: string }) =>
    api.post<FeedbackSummary>('/feedback', payload).then((r) => r.data),
};

// Refer & Earn MVP -- mirrors backend ReferralDtos exactly. Just a code and a count, ported from
// frontend/src/api/endpoints.ts's own copy.
export interface MyReferralsDto {
  code: string;
  referralCount: number;
}

export const referralsApi = {
  myCode: () => api.get<{ code: string }>('/referrals/my-code').then((r) => r.data),
  mine: () => api.get<MyReferralsDto>('/referrals/mine').then((r) => r.data),
};

/** Subscription billing V4. Mirrors frontend's EntitlementsDto exactly -- backend endpoint
 *  (GET /api/v1/entitlements) is unchanged; this is the mobile client that never existed before. */
export interface EntitlementsDto {
  planCode: string | null;
  planName: string | null;
  features: Record<string, boolean>;
}

export const entitlementsApi = {
  mine: () => api.get<EntitlementsDto>('/entitlements').then((r) => r.data),
};

/** Subscription billing V4. Mirrors frontend's MySubscription exactly (mobile only ever reads
 *  this -- purchasing happens through RevenueCat's SDK, not a checkout()/changePlan() call). */
export interface MySubscription {
  planCode: string;
  planName: string;
  billingCycle: string | null;
  status: string;
  renewalDate: string | null;
  autoRenew: boolean;
  hasBillingSubscription: boolean;
  paymentProvider: string | null;
}

export const billingApi = {
  mySubscription: () => api.get<MySubscription>('/billing/subscription').then((r) => r.data),
  // Pause/resume are real actions here despite the "mobile only ever reads" note above -- unlike
  // checkout/cancel, they neither create a subscription nor move ownership between providers (the
  // ownership-source rule design spec V4 §2.1 invariant 2 is scoped to those two), so a
  // Razorpay-owned subscription viewed on mobile can still be paused/resumed from here.
  pause: () => api.post<{ message: string }>('/billing/pause').then((r) => r.data),
  resume: () => api.post<{ message: string }>('/billing/resume').then((r) => r.data),
};
