/**
 * The line an admin sees when a list or report failed to load. Prefers the server's own message
 * (a 403 or 503 says why); the fallback covers a network failure, which has no response at all.
 */
export function loadErrorMessage(error: unknown): string {
  const message = (error as { response?: { data?: { message?: unknown } } } | null)?.response?.data?.message;
  return typeof message === 'string' && message.trim() !== ''
    ? `Couldn't load this list: ${message}`
    : "Couldn't load this list. Please try again.";
}
