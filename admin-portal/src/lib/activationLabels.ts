import type { ActivationAnswerName, ActivationMomentName, DeletionReasonName, FunnelStep, SignupSourceKind } from '../api/endpoints';

/** Gate 2a spec §6: the funnel columns, in rank order (FunnelStep.java). */
export const STEP_LABELS: Record<FunnelStep, string> = {
  SIGNED_UP: 'Signed up',
  OPENED_IMPORT: 'Opened import',
  CHOSE_FILE: 'Chose a file',
  REFUSED: 'Refused',
  READ_FAILED: 'Read failed',
  HELD: 'Held',
  READ_NOT_REVIEWED: 'Read, not reviewed',
  REVIEWED_NOT_CONFIRMED: 'Reviewed, not confirmed',
  GOT_DATA_IN: 'Got data in',
};
export const STEP_ORDER = Object.keys(STEP_LABELS) as FunnelStep[];

/** Each moment's question, word for word as the apps ask it (spec §4.1). */
export const MOMENT_QUESTIONS: Record<ActivationMomentName, string> = {
  NO_FILE: 'Last time you looked at adding a statement. What stopped you?',
  REFUSED: 'What will you do now?',
  NOT_CONFIRMED: 'What made you stop?',
};

/** The answers as the apps show them (spec §4.1); SKIPPED is the visible "Skip". */
export const ANSWER_LABELS: Record<ActivationAnswerName, string> = {
  NO_STATEMENT: "I don't have my statement",
  NOT_COMFORTABLE: "I'm not comfortable uploading it",
  DIDNT_KNOW_HOW: "I didn't know how",
  NO_POINT_YET: "I didn't see the point yet",
  NOT_NOW: 'Not now',
  TRY_ANOTHER_FILE: 'Try another file',
  NO_OTHER_STATEMENT: "I don't have another statement",
  DONT_UNDERSTAND_FAILURE: "I don't understand why it failed",
  LEAVE_FOR_NOW: 'Leave it for now',
  ROWS_LOOKED_WRONG: 'The rows looked wrong',
  WRONG_FILE: 'I uploaded the wrong file',
  NOT_READY_TO_SAVE: "I'm not ready to save it",
  COME_BACK_LATER: "I'll come back to it",
  SKIPPED: 'Skip',
};

/** Spec §5, word for word. */
export const DELETION_REASON_LABELS: Record<DeletionReasonName, string> = {
  COULDNT_GET_STATEMENTS_IN: "I couldn't get my statements in",
  NOT_COMFORTABLE_WITH_DATA: "I'm not comfortable with my data here",
  NOTHING_USEFUL: "It didn't show me anything useful",
  USE_ANOTHER_APP: 'I use another app',
  DONT_NEED_IT: "I don't need it anymore",
  SOMETHING_ELSE: 'Something else',
};

/** The onboarding focus chips, as the web app words them (FinancialFocusScreen), without the emoji. */
export const FOCUS_LABELS: Record<string, string> = {
  TRACK_SPENDING: 'Track my spending',
  MANAGE_BUDGETS: 'Create and manage budgets',
  SAVE_FOR_GOAL: 'Save for a goal',
  SEE_ALL_ACCOUNTS: 'See all my accounts in one place',
  IMPROVE_HABITS: 'Improve my financial habits',
  REDUCE_DEBT: 'Reduce debt',
  EXPLORING: 'Just exploring',
};

/** The required setup question's answers, in the order users see them (SpendingTrackingQuestion). */
export const SPENDING_TRACKING_LABELS: Record<string, string> = {
  NOT_TRACKED: "Don't really track it",
  IN_MY_HEAD: 'Roughly, in their head',
  PAPER: 'Notebook or paper',
  SPREADSHEET: 'Spreadsheet (Excel, Google Sheets)',
  EXPENSE_APP: 'Expense or budgeting app',
  BANK_APP: "Their bank's app or statements",
  OTHER: 'Something else',
};

/** The server's label for someone who did not answer an onboarding question. */
export const NO_ANSWER = 'NONE';
export const NO_ANSWER_LABEL = 'No answer';

/** The fixed source buckets (SignupSourceKind.java); a UTM row is labelled by its tag value instead. */
export const SOURCE_LABELS: Record<SignupSourceKind, string> = {
  UTM: 'Tagged link',
  GOOGLE: 'Google',
  BING: 'Bing',
  OTHER_SEARCH: 'Other search engines',
  SOCIAL: 'Social sites',
  OTHER_SITE: 'Other sites',
  REFERRAL: "Friend's referral code",
  DIRECT: 'Direct (incl. mobile app)',
  UNKNOWN_BEFORE_TRACKING: 'Unknown (before tracking)',
};

/** Days to one decimal, or a dash when nobody has a second import. */
export function formatDays(days: number | null): string {
  if (days === null) return '—';
  return `${days.toFixed(1)} ${days === 1 ? 'day' : 'days'}`;
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "2026-10-17" as "17 Oct 2026", read from the string itself: going through Date would shift the
 *  day for an admin whose browser sits west of UTC. */
export function formatIsoDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return `${d} ${MONTHS[m - 1]} ${y}`;
}

/** "2026-10-01" as "Oct 2026". */
export function formatIsoMonth(iso: string): string {
  const [y, m] = iso.split('-').map(Number);
  return `${MONTHS[m - 1]} ${y}`;
}
