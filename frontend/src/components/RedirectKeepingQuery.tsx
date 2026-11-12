import { Navigate, useLocation } from 'react-router-dom';

/**
 * A redirect that keeps the address's query string and fragment. A bare `<Navigate to="/auth" />`
 * drops them: `/register?ref=CODE` -- the referral link the Refer & Earn page and the invite card
 * share -- landed on `/auth` with no `ref`, so the friend's code was never filled in and the
 * referral was never credited.
 */
export function RedirectKeepingQuery({ to }: { to: string }) {
  const { search, hash } = useLocation();
  return <Navigate to={{ pathname: to, search, hash }} replace />;
}
