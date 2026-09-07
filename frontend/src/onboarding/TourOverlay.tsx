import { useLayoutEffect, useRef, useState } from 'react';
import { Button } from '../design-system';
import type { TourStep } from './tourSteps';

interface Props {
  steps: TourStep[];
  onFinish: () => void;
  onSkip: () => void;
}

const EDGE_MARGIN = 12;

export function TourOverlay({ steps, onFinish, onSkip }: Props) {
  const [index, setIndex] = useState(0);
  const [rect, setRect] = useState<DOMRect | null>(null);
  // Bug fix: the tooltip used to render unconditionally at `rect.bottom + 12` / `rect.left` --
  // for any target near the bottom or right edge of the viewport (the Sidebar's later items on a
  // modest-height window, or simply a future addition further down the nav), that pushes the card
  // partially or fully off-screen. There's no scroll-into-view fallback for it either: the card is
  // positioned `absolute` inside a `fixed inset-0` wrapper, so it's pinned to the viewport, not the
  // document -- scrolling the page can never bring it back into view. An off-screen card means its
  // Next/Skip/Back buttons are unreachable, i.e. the tour is stuck. `cardPos` is the clamped
  // position actually used to render; `null` until the first measurement below has run.
  const [cardPos, setCardPos] = useState<{ top: number; left: number } | null>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const step = steps[index];
  const isLast = index === steps.length - 1;

  // Deliberately one effect, not two: re-measuring the target and re-clamping the card against it
  // both key off step.targetSelector alone (not off `rect` state), so both happen in the same
  // synchronous pass on a step change -- the card is never clamped against the PREVIOUS step's
  // target position for even one frame while `rect` state catches up. useLayoutEffect (not
  // useEffect) runs before the browser paints, so setting both `rect` and `cardPos` here shows up
  // in the very first frame of the new step rather than flashing the previous, unclamped layout
  // first. Below-the-target is preferred (matches the spotlight ring sitting above it); flips
  // above the target only when below would overflow the viewport, and horizontal placement is
  // clamped independently of that flip.
  useLayoutEffect(() => {
    const target = document.querySelector(step.targetSelector);
    const targetRect = target ? target.getBoundingClientRect() : null;
    setRect(targetRect);

    const card = cardRef.current;
    if (!card) return;
    const { width, height } = card.getBoundingClientRect();
    if (!targetRect) {
      setCardPos({
        top: window.innerHeight / 2 - height / 2,
        left: window.innerWidth / 2 - width / 2,
      });
      return;
    }
    let top = targetRect.bottom + EDGE_MARGIN;
    if (top + height > window.innerHeight - EDGE_MARGIN) {
      top = Math.max(EDGE_MARGIN, targetRect.top - height - EDGE_MARGIN);
    }
    const left = Math.min(
      Math.max(EDGE_MARGIN, targetRect.left),
      window.innerWidth - width - EDGE_MARGIN
    );
    setCardPos({ top, left });
  }, [step.targetSelector]);

  function next() {
    if (isLast) {
      onFinish();
    } else {
      setIndex((i) => i + 1);
    }
  }

  function back() {
    setIndex((i) => Math.max(0, i - 1));
  }

  return (
    <div className="fixed inset-0 z-50" role="dialog" aria-label="Product tour">
      <div className="absolute inset-0 bg-black/60" />
      {rect && (
        <div
          className="absolute rounded-lg ring-4 ring-primary pointer-events-none"
          style={{ top: rect.top - 4, left: rect.left - 4, width: rect.width + 8, height: rect.height + 8 }}
        />
      )}
      <div
        ref={cardRef}
        className="absolute bg-card rounded-lg shadow-xl p-5 max-w-xs"
        // Rendered at cardPos once useLayoutEffect has measured and clamped it; until then (first
        // paint of a new step), the same unclamped fallback the fix replaces -- correct for the
        // common case, and immediately superseded before the browser actually paints if it wasn't.
        style={
          cardPos ?? (rect
            ? { top: rect.bottom + EDGE_MARGIN, left: rect.left }
            : { top: '50%', left: '50%', transform: 'translate(-50%,-50%)' })
        }
      >
        <h3 className="font-bold text-ink mb-1">{step.title}</h3>
        <p className="text-sm text-muted mb-4">{step.body}</p>
        <div className="flex items-center justify-between">
          <button type="button" className="text-xs text-muted" onClick={onSkip}>Skip</button>
          <div className="flex gap-2">
            {index > 0 && <Button variant="secondary" size="sm" onClick={back}>Back</Button>}
            <Button variant="primary" size="sm" onClick={next}>{isLast ? 'Finish' : 'Next'}</Button>
          </div>
        </div>
      </div>
    </div>
  );
}
