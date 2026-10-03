import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { transactionsApi } from '../api/endpoints';
import { Button } from '../design-system';

/**
 * After an import: "Sort N questions", opening the Ledger where Quick sort asks them. Counts the
 * first batch with preview=true, so showing the number is not recorded as a batch shown. Renders
 * nothing when nothing is waiting or the count cannot be read -- the import itself succeeded.
 */
export function QuickSortCta() {
  const navigate = useNavigate();
  const [count, setCount] = useState(0);

  useEffect(() => {
    transactionsApi.quickSort(0, true)
      .then((b) => setCount(b.questions.length))
      .catch(() => setCount(0));
  }, []);

  if (count === 0) return null;
  return (
    <Button onClick={() => void navigate('/app/transactions')}>
      Sort {count} {count === 1 ? 'question' : 'questions'}
    </Button>
  );
}
