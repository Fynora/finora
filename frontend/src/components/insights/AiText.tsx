import { useState } from 'react';
import { Sparkles, ThumbsDown, ThumbsUp } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { aiInsightsApi, type AiFeedbackRating, type AiFeedbackReason } from '../../api/endpoints';

const REASONS: { value: AiFeedbackReason; label: string }[] = [
  { value: 'WRONG_NUMBER', label: 'Wrong number' },
  { value: 'NOT_USEFUL', label: 'Not useful' },
  { value: 'CONFUSING', label: 'Confusing' },
];

interface Props {
  /** The stored text's id; null on rules text, which has nothing to rate. */
  id: string | null;
  text: string;
  writtenByAi: boolean;
  /** What the text compares against, from the server. */
  basis: string | null;
  /** The thumb saved on this text earlier, as the server sent it. */
  rating?: AiFeedbackRating | null;
  ratingReason?: AiFeedbackReason | null;
  /** A thumb the server has saved, so its owner can keep its copy of the text in step. */
  onFeedback?: (rating: AiFeedbackRating | null, reason: AiFeedbackReason | null) => void;
}

/**
 * A text the server wrote for Insights (AI insights spec §8.4): on the website, the month story.
 * The website twin of mobile's AiText. AI text carries a "Written by AI" label and thumbs; rules
 * text is shown plainly. A thumbs-down offers a fixed list of reasons -- nothing the user types is
 * ever sent. Callers key it by id, so a new text starts from its own saved thumb.
 */
export function AiText({
  id, text, writtenByAi, basis, rating: savedRating = null, ratingReason: savedReason = null, onFeedback,
}: Props) {
  const [rating, setRating] = useState<AiFeedbackRating | null>(savedRating);
  const [reason, setReason] = useState<AiFeedbackReason | null>(savedRating === 'NOT_HELPFUL' ? savedReason : null);
  const rateable = writtenByAi && id !== null;

  function send(nextRating: AiFeedbackRating | null, nextReason: AiFeedbackReason | null) {
    if (!id) return;
    const before = { rating, reason };
    setRating(nextRating);
    setReason(nextReason);
    // Not saved: show what the server still holds rather than a thumb that did not count.
    aiInsightsApi.feedback(id, nextRating, nextReason)
      .then(() => onFeedback?.(nextRating, nextReason))
      .catch(() => {
        setRating(before.rating);
        setReason(before.reason);
      });
  }

  function thumb(value: AiFeedbackRating, label: string, Icon: LucideIcon) {
    const selected = rating === value;
    return (
      <button
        type="button"
        onClick={() => send(selected ? null : value, null)}
        aria-label={label}
        aria-pressed={selected}
        className={`h-8 w-8 inline-flex items-center justify-center rounded-full hover:bg-black/[0.04] ${selected ? 'text-primary' : 'text-muted'}`}
      >
        <Icon size={16} aria-hidden="true" fill={selected ? 'currentColor' : 'none'} />
      </button>
    );
  }

  return (
    <div className="space-y-1.5">
      {writtenByAi && (
        <p className="flex items-center gap-1 text-xs text-muted">
          <Sparkles size={12} aria-hidden="true" />
          Written by AI
        </p>
      )}
      <p className="text-sm leading-relaxed">{text}</p>
      {basis && <p className="text-xs text-muted">{basis}</p>}
      {rateable && (
        <div className="flex items-center gap-1">
          {thumb('HELPFUL', 'Helpful', ThumbsUp)}
          {thumb('NOT_HELPFUL', 'Not helpful', ThumbsDown)}
        </div>
      )}
      {rateable && rating === 'NOT_HELPFUL' && (
        reason ? (
          <p role="status" className="text-xs text-muted">Thanks for telling us.</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {REASONS.map((r) => (
              <button
                key={r.value}
                type="button"
                onClick={() => send('NOT_HELPFUL', r.value)}
                className="text-xs font-medium border border-border rounded-full px-3 py-1 hover:bg-black/[0.04]"
              >
                {r.label}
              </button>
            ))}
          </div>
        )
      )}
    </div>
  );
}
