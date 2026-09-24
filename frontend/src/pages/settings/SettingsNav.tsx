import type { ReactNode } from 'react';
import { SlidersHorizontal, ShieldCheck, Bell, Sparkles, Info, Mail, Landmark, UserX } from 'lucide-react';
import { GMAIL_SYNC_UI_ENABLED } from '../../lib/features';

const ALL_SETTINGS_CATEGORIES: { key: string; label: string; icon: ReactNode }[] = [
  { key: 'general', label: 'General', icon: <SlidersHorizontal size={16} /> },
  { key: 'security', label: 'Security', icon: <ShieldCheck size={16} /> },
  { key: 'notifications', label: 'Notifications', icon: <Bell size={16} /> },
  { key: 'categorization', label: 'Categorization', icon: <Sparkles size={16} /> },
  { key: 'data', label: 'Data', icon: <Info size={16} /> },
  { key: 'connected-apps', label: 'Connected Apps', icon: <Mail size={16} /> },
  { key: 'bank-sync', label: 'Bank Sync', icon: <Landmark size={16} /> },
  { key: 'account', label: 'Account', icon: <UserX size={16} /> },
];

// Connected Apps holds only Gmail sync, which is paused (see lib/features.ts), so the whole tab goes
// with it. Filtering the list, rather than editing it, also makes a bookmarked ?tab=connected-apps
// fall back to General instead of showing a pane that has no way in.
export const SETTINGS_CATEGORIES = ALL_SETTINGS_CATEGORIES.filter(
  (c) => c.key !== 'connected-apps' || GMAIL_SYNC_UI_ENABLED,
);

export function SettingsNav({ active, onSelect }: { active: string; onSelect: (key: string) => void }) {
  return (
    <nav className="w-56 flex-shrink-0 space-y-0.5" aria-label="Settings categories">
      {SETTINGS_CATEGORIES.map((c) => {
        const isActive = c.key === active;
        return (
          <button
            key={c.key}
            type="button"
            onClick={() => onSelect(c.key)}
            aria-current={isActive || undefined}
            className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm text-left transition-colors ${
              isActive ? 'bg-primary/10 text-primary font-medium' : 'text-muted hover:bg-card hover:text-ink'
            }`}
          >
            {c.icon}
            {c.label}
          </button>
        );
      })}
    </nav>
  );
}
