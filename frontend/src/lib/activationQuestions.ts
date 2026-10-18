import type { ActivationAnswer, ActivationMoment, DeletionReason } from '../api/endpoints';

/**
 * "What stopped you?" (Gate 2a spec §4.1): each moment's question and its fixed answers, word for
 * word and in order. Mirrored in mobile/src/lib/activationQuestions.ts; the server holds the same
 * answer lists (ActivationAnswer.java) and refuses an answer from another moment's list.
 */
export const ACTIVATION_QUESTIONS: Record<ActivationMoment, { question: string; answers: { key: ActivationAnswer; label: string }[] }> = {
  NO_FILE: {
    question: 'Last time you looked at adding a statement. What stopped you?',
    answers: [
      { key: 'NO_STATEMENT', label: "I don't have my statement" },
      { key: 'NOT_COMFORTABLE', label: "I'm not comfortable uploading it" },
      { key: 'DIDNT_KNOW_HOW', label: "I didn't know how" },
      { key: 'NO_POINT_YET', label: "I didn't see the point yet" },
      { key: 'NOT_NOW', label: 'Not now' },
    ],
  },
  REFUSED: {
    question: 'What will you do now?',
    answers: [
      { key: 'TRY_ANOTHER_FILE', label: 'Try another file' },
      { key: 'NO_OTHER_STATEMENT', label: "I don't have another statement" },
      { key: 'DONT_UNDERSTAND_FAILURE', label: "I don't understand why it failed" },
      { key: 'LEAVE_FOR_NOW', label: 'Leave it for now' },
    ],
  },
  NOT_CONFIRMED: {
    question: 'What made you stop?',
    answers: [
      { key: 'ROWS_LOOKED_WRONG', label: 'The rows looked wrong' },
      { key: 'WRONG_FILE', label: 'I uploaded the wrong file' },
      { key: 'NOT_READY_TO_SAVE', label: "I'm not ready to save it" },
      { key: 'COME_BACK_LATER', label: "I'll come back to it" },
    ],
  },
};

export const SKIP_LABEL = 'Skip';

/**
 * The server takes one answer or skip a day (spec §4.3) and refuses the rest with 409. Once today is
 * answered -- by a tap here, or by a 409 saying it already was -- no further question is shown today,
 * so nobody taps an answer that silently goes nowhere. Kept for this app session only; the local day
 * can differ from the server's reporting-zone day around midnight, which only errs towards asking
 * less.
 */
let answeredOn: string | null = null;

function localDay(): string {
  const d = new Date();
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
}

export function markActivationAnsweredToday(): void {
  answeredOn = localDay();
}

export function activationAnsweredToday(): boolean {
  return answeredOn === localDay();
}

/** At sign-out: today's answer belongs to the account that gave it, not the next one to sign in. */
export function forgetActivationAnswered(): void {
  answeredOn = null;
}

/** Test seam: the memory is module state, which persists across the tests in one file. */
export const resetActivationAnsweredForTests = forgetActivationAnswered;

/**
 * Gate 2a spec §5: the optional question when someone deletes their account, word for word and in
 * order. The server holds the same list (DeletionReason.java) and keeps the answer with the month
 * only and no link to the person.
 */
export const DELETION_REASON_QUESTION = "Before you go: what's the main reason?";
export const DELETION_REASONS: { key: DeletionReason; label: string }[] = [
  { key: 'COULDNT_GET_STATEMENTS_IN', label: "I couldn't get my statements in" },
  { key: 'NOT_COMFORTABLE_WITH_DATA', label: "I'm not comfortable with my data here" },
  { key: 'NOTHING_USEFUL', label: "It didn't show me anything useful" },
  { key: 'USE_ANOTHER_APP', label: 'I use another app' },
  { key: 'DONT_NEED_IT', label: "I don't need it anymore" },
  { key: 'SOMETHING_ELSE', label: 'Something else' },
];
