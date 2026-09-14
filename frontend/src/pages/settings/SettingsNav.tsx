import type { ReactNode } from 'react';
import { SlidersHorizontal, ShieldCheck, Sparkles, Info, Mail, Landmark, UserX } from 'lucide-react';

export const SETTINGS_CATEGORIES: { key: string; label: string; icon: ReactNode }[] = [
  { key: 'general', label: 'General', icon: <SlidersHorizontal size={16} /> },
  { key: 'security', label: 'Security', icon: <ShieldCheck size={16} /> },
  { key: 'categorization', label: 'Categorization', icon: <Sparkles size={16} /> },
  { key: 'data', label: 'Data', icon: <Info size={16} /> },
  { key: 'connected-apps', label: 'Connected Apps', icon: <Mail size={16} /> },
  { key: 'bank-sync', label: 'Bank Sync', icon: <Landmark size={16} /> },
  { key: 'account', label: 'Account', icon: <UserX size={16} /> },
];

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
