import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { activationApi } from '../../api/endpoints';
import { FinoraCard } from '../../design-system';
import { WhatStoppedYou } from './WhatStoppedYou';
import { activationAnsweredToday, markActivationAnsweredToday } from '../../lib/activationQuestions';

/**
 * The dashboard's "What stopped you?" card (Gate 2a spec §4.1-4.2). The server decides whether
 * there is a question and which one; this renders nothing otherwise, and nothing while that is
 * loading or could not be fetched. Closing the card counts as Skip, so it is not asked again for a
 * while -- a card nobody touches stays until it is answered or no longer applies.
 */
export function ActivationQuestionCard() {
  // Read inside the query function, not at render: if the call cannot even be made, that is a failed
  // query (no card), never a broken dashboard.
  const { data: moment, isFetchedAfterMount } = useQuery({
    queryKey: ['activation', 'question'], queryFn: () => activationApi.question(),
  });
  const [closed, setClosed] = useState(false);
  // Whether today's answer was given here: it leaves its "Thanks." showing.
  const [answered, setAnswered] = useState(false);

  // Only an answer fetched for this visit: one cached from an earlier visit may no longer apply (a
  // first import since then ends NO_FILE), and flashing it while the new answer loads would ask
  // someone why they never added a statement right after they added one.
  // Nor once today is answered elsewhere (checked at every render: the dashboard can stay mounted
  // while the import screen takes an answer): the question inside would not show, leaving an empty card.
  if (!isFetchedAfterMount || !moment || closed || (activationAnsweredToday() && !answered)) return null;

  function close() {
    setClosed(true);
    // After an answer, closing only hides the "Thanks." -- today's one answer is already in.
    if (answered) return;
    // Fire and forget, like the answers: closing must never surface an error. It is today's answer
    // (or a 409 says one was given elsewhere), so no other question shows today.
    activationApi.answer(moment!, 'SKIPPED').then(
      () => markActivationAnsweredToday(),
      (e: { response?: { status?: number } }) => { if (e?.response?.status === 409) markActivationAnsweredToday(); },
    );
  }

  return (
    <FinoraCard padding="lg" className="mb-6 relative">
      <button
        type="button"
        onClick={close}
        aria-label="Close"
        className="absolute top-3 right-3 p-1 rounded-md text-muted hover:text-ink hover:bg-bg"
      >
        <X size={16} aria-hidden="true" />
      </button>
      <div className="pr-8">
        {/* A refused answer hides the question; close the card with it rather than leave it empty. */}
        <WhatStoppedYou
          moment={moment}
          chosenByServer
          onDone={(outcome) => {
            if (outcome === 'answered') setAnswered(true);
            else setClosed(true);
          }}
        />
      </div>
    </FinoraCard>
  );
}
