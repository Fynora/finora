import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { activationApi } from '../../api/endpoints';
import { FinoraCard } from '../../design-system';
import { WhatStoppedYou } from './WhatStoppedYou';

/**
 * The dashboard's "What stopped you?" card (Gate 2a spec §4.1-4.2). The server decides whether
 * there is a question and which one; this renders nothing otherwise, and nothing while that is
 * loading or could not be fetched. Closing the card counts as Skip, so it is not asked again for a
 * while -- a card nobody touches stays until it is answered or no longer applies.
 */
export function ActivationQuestionCard() {
  // Read inside the query function, not at render: if the call cannot even be made, that is a failed
  // query (no card), never a broken dashboard.
  const { data: moment } = useQuery({ queryKey: ['activation', 'question'], queryFn: () => activationApi.question() });
  const [closed, setClosed] = useState(false);

  if (!moment || closed) return null;

  function close() {
    setClosed(true);
    // Fire and forget, like the answers: closing must never surface an error.
    activationApi.answer(moment!, 'SKIPPED').catch(() => {});
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
        <WhatStoppedYou moment={moment} />
      </div>
    </FinoraCard>
  );
}
