import { useEffect, useState } from 'react';
import { Button } from './Button';
import { transactionsApi } from '../api/endpoints';

/**
 * After an import: "Sort N questions", opening Quick sort. Counts the first batch with
 * preview=true, so showing the number is not recorded as a batch shown. Renders nothing when
 * nothing is waiting or the count cannot be read -- the import itself succeeded either way.
 */
export function QuickSortCta({ onPress }: { onPress: () => void }) {
  const [count, setCount] = useState(0);

  useEffect(() => {
    transactionsApi.quickSort(0, true)
      .then((b) => setCount(b.questions.length))
      .catch(() => setCount(0));
  }, []);

  if (count === 0) return null;
  return <Button label={`Sort ${count} ${count === 1 ? 'question' : 'questions'}`} onPress={onPress} />;
}
