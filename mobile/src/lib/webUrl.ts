// The web app's own absolute origin -- distinct from EXPO_PUBLIC_API_BASE_URL (the backend API).
// Fynora's Privacy Policy and Terms of Service (frontend/src/pages/Privacy.tsx, Terms.tsx) are
// marketing/legal routes on the web app, not the backend; mobile has no in-app copies of them and
// links out to these instead, the same way the backend's own app-base-url is what powers the web
// links it emails (see ForgotPasswordScreen's doc comment on APP_BASE_URL). Falls back to
// production rather than throwing like client.ts's API base does -- a wrong/missing value here
// just opens the wrong environment's copy of a static page, not a broken app.
const APP_BASE_URL = (process.env.EXPO_PUBLIC_APP_BASE_URL || 'https://app.fynora.net').replace(/\/+$/, '');

export function webUrl(path: string): string {
  return `${APP_BASE_URL}${path.startsWith('/') ? path : `/${path}`}`;
}
