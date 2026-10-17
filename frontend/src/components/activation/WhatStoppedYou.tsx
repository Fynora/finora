import { useRef, useState } from 'react';
import { activationApi, type ActivationAnswer, type ActivationMoment } from '../../api/endpoints';
import {
  ACTIVATION_QUESTIONS, SKIP_LABEL, activationAnsweredToday, markActivationAnsweredToday,
} from '../../lib/activationQuestions';

/**
 * One "What stopped you?" question (Gate 2a spec §4): one tap on a fixed answer, or Skip. No free
 * text, by design -- a typed answer could carry amounts, names or account numbers.
 *
 * After a tap it says "Thanks." and calls `onDone`. If the answer is refused (409: the question is
 * not being asked now) or fails at all, it disappears without an error and still calls `onDone` --
 * a question about why someone stopped must never become one more thing in their way.
 */
export function WhatStoppedYou({ moment, onDone }: {
  moment: ActivationMoment;
  /** 'answered': it now says "Thanks.". 'refused': it hid itself (409 or any failure). */
  onDone?: (outcome: 'answered' | 'refused') => void;
}) {
  // Not shown at all once today is answered: the server would refuse this one (spec §4.3).
  const [state, setState] = useState<'asking' | 'thanks' | 'gone'>(() => (activationAnsweredToday() ? 'gone' : 'asking'));
  const sent = useRef(false);
  const { question, answers } = ACTIVATION_QUESTIONS[moment];

  function send(answer: ActivationAnswer) {
    // A ref, not state: a second tap in the same tick must not send a second answer.
    if (sent.current) return;
    sent.current = true;
    activationApi.answer(moment, answer).then(
      () => {
        markActivationAnsweredToday();
        setState('thanks');
        onDone?.('answered');
      },
      (e: { response?: { status?: number } }) => {
        // 409: today (or this moment) is already answered. Any other failure says nothing about it.
        if (e?.response?.status === 409) markActivationAnsweredToday();
        setState('gone');
        onDone?.('refused');
      },
    );
  }

  if (state === 'gone') return null;
  if (state === 'thanks') {
    return <p className="text-sm text-muted" role="status">Thanks.</p>;
  }
  return (
    <div role="group" aria-label={question} className="space-y-2" data-testid={`what-stopped-you-${moment}`}>
      <p className="text-sm font-medium text-ink">{question}</p>
      <div className="flex flex-wrap gap-2">
        {answers.map((a) => (
          <button
            key={a.key}
            type="button"
            onClick={() => send(a.key)}
            className="px-3 py-1.5 rounded-lg border border-border text-xs text-ink hover:bg-bg transition-colors"
          >
            {a.label}
          </button>
        ))}
        <button
          type="button"
          onClick={() => send('SKIPPED')}
          className="px-3 py-1.5 rounded-lg text-xs text-muted hover:underline"
        >
          {SKIP_LABEL}
        </button>
      </div>
    </div>
  );
}
