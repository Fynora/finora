import { useCallback, useEffect, useRef, useState } from 'react';

const DISPLAY_MS = 3000;

/**
 * Issue #1451 -- the "Fynora will remember this" reinforcement copy shown after a real action
 * that deepens the personalized categorization/merchant-recognition moat (a category correction
 * today; see the ticket for which other actions this backs). Deliberately plain: a message and a
 * timeout, no queue -- a second show() while one is already visible replaces it and restarts the
 * clock rather than stacking multiple messages, since these only ever fire one at a time from a
 * single user action.
 */
export function useMemoryReinforcement() {
  const [message, setMessage] = useState<string | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
  }, []);

  const show = useCallback((text: string) => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    setMessage(text);
    timeoutRef.current = setTimeout(() => setMessage(null), DISPLAY_MS);
  }, []);

  return { message, show };
}
