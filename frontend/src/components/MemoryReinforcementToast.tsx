import { Archive } from 'lucide-react';

// Issue #1451. Deliberately plain per the conversion-psychology framework's governing rule: a
// factual note, not a celebratory toast -- no confetti, no color-coded "achievement" styling,
// same restraint the Financial Memory Completeness Dashboard (#1450) already applies.
export function MemoryReinforcementToast({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div
      role="status"
      // Bottom-center, not bottom-right: Dashboard.tsx has a floating "add transaction" button
      // fixed at bottom-8 right-8, which a bottom-right toast would sit on top of.
      className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 max-w-xs bg-card border border-border rounded-lg shadow-soft px-4 py-3 flex items-start gap-2.5 text-sm text-ink"
    >
      <Archive size={16} className="text-primary flex-shrink-0 mt-0.5" />
      <span>{message}</span>
    </div>
  );
}
