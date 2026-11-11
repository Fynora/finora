import { loadErrorMessage } from '../lib/loadError';

/** The failed-load line for sections that aren't a DataTable. Renders nothing without an error.
 *  `what` names the thing that failed, e.g. "these numbers", when it isn't a list. */
export function LoadError({ error, what, className = '' }: { error: unknown; what?: string; className?: string }) {
  if (error == null) return null;
  return <p role="alert" className={`text-sm text-danger ${className}`}>{loadErrorMessage(error, what)}</p>;
}
