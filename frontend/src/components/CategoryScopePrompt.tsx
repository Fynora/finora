import { useRef, useState, type ReactNode } from 'react';
import { transactionsApi, type CategoryScope, type SimilarSummary } from '../api/endpoints';
import { useDialogA11y } from '../design-system';

/**
 * The answer to "this category, for which rows?":
 * - a {@link CategoryScope} the user picked, or SIMILAR when there are no other rows to ask about;
 * - `undefined` when the similar-rows lookup failed: send no scope, and the server keeps its
 *   behaviour from before the choice existed (this row only, remembered for future imports);
 * - `null` when the user closed the question: save nothing.
 */
export type ScopeAnswer = CategoryScope | undefined | null;

interface Pending {
  summary: SimilarSummary;
  resolve: (answer: ScopeAnswer) => void;
}

/**
 * Asks "All N from this payee, or only this one?" before a category change, and only when there is
 * something to ask: no other row from the same payee in the same direction means no question.
 *
 * Usage: `const { ask, prompt } = useCategoryScopePrompt();` render `prompt`, then
 * `const scope = await ask(id); if (scope === null) return;` before saving.
 */
export function useCategoryScopePrompt(): { ask: (id: string) => Promise<ScopeAnswer>; prompt: ReactNode } {
  const [pending, setPending] = useState<Pending | null>(null);
  // One question at a time: a second ask() while one is open answers the first as closed.
  const pendingRef = useRef<Pending | null>(null);

  async function ask(id: string): Promise<ScopeAnswer> {
    let summary: SimilarSummary;
    try {
      summary = await transactionsApi.similar(id);
    } catch {
      return undefined;
    }
    if (summary.similar === 0) return 'SIMILAR';
    pendingRef.current?.resolve(null);
    return new Promise<ScopeAnswer>((resolve) => {
      const next = { summary, resolve };
      pendingRef.current = next;
      setPending(next);
    });
  }

  function answer(value: ScopeAnswer) {
    pendingRef.current?.resolve(value);
    pendingRef.current = null;
    setPending(null);
  }

  const prompt = pending ? <CategoryScopeDialog summary={pending.summary} onAnswer={answer} /> : null;
  return { ask, prompt };
}

function plural(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`;
}

export function CategoryScopeDialog({ summary, onAnswer }: {
  summary: SimilarSummary;
  onAnswer: (answer: ScopeAnswer) => void;
}) {
  const panelRef = useDialogA11y({ onClose: () => onAnswer(null) });
  const all = summary.similar + 1;
  return (
    // z-50: it opens on top of the Ledger's edit dialog (z-40) as well as on a page.
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50" onClick={() => onAnswer(null)}
      data-testid="category-scope-dialog">
      <div ref={panelRef} role="dialog" aria-modal="true" aria-labelledby="category-scope-title" tabIndex={-1}
        className="bg-card rounded-xl2 shadow-card p-6 w-[420px] max-w-[90vw]" onClick={(e) => e.stopPropagation()}>
        <h2 id="category-scope-title" className="font-semibold text-ink text-sm mb-2">
          Change the others from this payee too?
        </h2>
        <p className="text-xs text-muted mb-1">
          {plural(summary.similar, 'other transaction', 'other transactions')} from the same payee can take this category.
          Choosing all also uses it for this payee&apos;s future transactions.
        </p>
        {summary.keptByUser > 0 && (
          <p className="text-xs text-muted mb-1">
            {plural(summary.keptByUser, 'transaction', 'transactions')} you categorised yourself will keep {summary.keptByUser === 1 ? 'its' : 'their'} category.
          </p>
        )}
        <div className="flex justify-end gap-2 pt-4">
          <button type="button" onClick={() => onAnswer('ONLY_THIS')}
            className="border border-border rounded-lg px-4 py-2 text-xs font-medium text-ink hover:bg-black/5">
            Only this one
          </button>
          <button type="button" onClick={() => onAnswer('SIMILAR')}
            className="bg-primary text-on-primary hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold">
            All {all}
          </button>
        </div>
      </div>
    </div>
  );
}
