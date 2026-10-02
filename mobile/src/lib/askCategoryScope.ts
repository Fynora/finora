import { transactionsApi, type CategoryScope } from '../api/endpoints';
import { AppAlert } from './appAlert';

/**
 * The answer to "this category, for which rows?":
 * - a {@link CategoryScope} the user picked, or SIMILAR when there are no other rows to ask about;
 * - `undefined` when the similar-rows lookup failed: send no scope, and the server keeps its
 *   behaviour from before the choice existed (this row only, remembered for future imports);
 * - `null` when the user cancelled: save nothing.
 */
export type ScopeAnswer = CategoryScope | undefined | null;

function plural(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`;
}

/**
 * Asks "All N from this payee, or only this one?" before a category change, and only when there is
 * something to ask: no other row from the same payee in the same direction means no question.
 * Mirrors the web app's useCategoryScopePrompt, shown as an AppAlert.
 */
export async function askCategoryScope(id: string): Promise<ScopeAnswer> {
  let summary;
  try {
    summary = await transactionsApi.similar(id);
  } catch {
    return undefined;
  }
  if (summary.similar === 0) return 'SIMILAR';
  const kept = summary.keptByUser > 0
    ? ` ${plural(summary.keptByUser, 'transaction', 'transactions')} you categorised yourself will keep ${summary.keptByUser === 1 ? 'its' : 'their'} category.`
    : '';
  const message = `${plural(summary.similar, 'other transaction', 'other transactions')} from the same payee can take this category. `
    + `Choosing all also uses it for this payee's future transactions.${kept}`;
  return new Promise<ScopeAnswer>((resolve) => {
    AppAlert.alert('Change the others from this payee too?', message, [
      { text: 'Cancel', style: 'cancel', onPress: () => resolve(null) },
      { text: 'Only this one', onPress: () => resolve('ONLY_THIS') },
      { text: `All ${summary.similar + 1}`, onPress: () => resolve('SIMILAR') },
    ], { cancelable: true, onDismiss: () => resolve(null) });
  });
}
