import { Link } from 'react-router-dom';

/**
 * Shared two-tab header for Settings.tsx and Billing.tsx -- Billing has no entry of its own in
 * the sidebar or account menu anymore (folded into Settings per design request), so this is the
 * only way to move between the two once you're on either page.
 */
export function SettingsTabs({ active }: { active: 'preferences' | 'billing' }) {
  return (
    <div className="inline-flex items-center gap-1 bg-bg border border-border rounded-lg p-1">
      <Link
        to="/app/settings"
        className={`text-xs font-semibold px-3.5 py-1.5 rounded-md transition-colors ${active === 'preferences' ? 'bg-card shadow-card text-ink' : 'text-muted hover:text-ink'}`}
      >
        Preferences
      </Link>
      <Link
        to="/app/billing"
        className={`text-xs font-semibold px-3.5 py-1.5 rounded-md transition-colors ${active === 'billing' ? 'bg-card shadow-card text-ink' : 'text-muted hover:text-ink'}`}
      >
        Billing &amp; Membership
      </Link>
    </div>
  );
}
