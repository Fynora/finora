import type { ActivationAnswer, ActivationMoment } from '../api/endpoints';

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

/** Test seam: the memory is module state, which persists across the tests in one file. */
export function resetActivationAnsweredForTests(): void {
  answeredOn = null;
}
