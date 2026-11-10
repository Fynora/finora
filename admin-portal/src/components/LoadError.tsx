import { loadErrorMessage } from '../lib/loadError';

/** The failed-load line for sections that aren't a DataTable. Renders nothing without an error. */
export function LoadError({ error, className = '' }: { error: unknown; className?: string }) {
  if (error == null) return null;
  return <p role="alert" className={`text-sm text-danger ${className}`}>{loadErrorMessage(error)}</p>;
}
