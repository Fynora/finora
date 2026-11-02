import { useEffect, useState } from 'react';
import { Button } from './Button';
import { transactionsApi } from '../api/endpoints';

/**
 * After an import: "Sort N questions", opening Quick sort. Counts the first batch with
 * preview=true, so showing the number is not recorded as a batch shown. Renders nothing when
 * nothing is waiting or the count cannot be read -- the import itself succeeded either way.
 */
export function QuickSortCta({ onPress, onCount }: { onPress: () => void; onCount?: (count: number) => void }) {
  const [count, setCount] = useState(0);
  // Tells the caller how many it found, so the caller can choose its own main button (Import's
  // done step makes "See Insights" the main one when nothing is waiting). Pass a stable function
  // (a state setter): a new one each render would ask again.
  useEffect(() => {
    transactionsApi.quickSort(0, true)
      .then((b) => b.questions.length)
      .catch(() => 0)
      .then((n) => {
        setCount(n);
        onCount?.(n);
      });
  }, [onCount]);

  if (count === 0) return null;
  return <Button label={`Sort ${count} ${count === 1 ? 'question' : 'questions'}`} onPress={onPress} />;
}
