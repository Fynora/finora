/**
 * The shared navigation taxonomy, as data.
 *
 * Deliberately separate from how either client renders it: this file says which destinations
 * exist and which group each belongs to, and nothing about sidebars, tabs or headers. That split
 * is what lets analytics attribute a group to every event before the grouped UI ships -- the
 * before-period and after-period are then directly comparable, which they would not be if `group`
 * only started being recorded once the grouped navigation shipped.
 *
 * mobile/src/navigation/taxonomy.ts must stay identical, and the backend's NavDestination enum
 * must agree on the id set. scripts/check-nav-taxonomy-drift.py enforces both in CI: a destination
 * present in one place and not another is a silent measurement gap, not a build error.
 */
export type NavGroupId = 'root' | 'money' | 'statements' | 'planning' | 'analysis' | 'your-account';

export type NavEntry = { readonly id: string; readonly group: NavGroupId; readonly label: string };

export const NAV_TAXONOMY: readonly NavEntry[] = [
  { id: 'home', group: 'root', label: 'Home' },

  { id: 'accounts', group: 'money', label: 'Accounts' },
  { id: 'transactions', group: 'money', label: 'Transactions' },

  { id: 'import-statement', group: 'statements', label: 'Import Statement' },
  { id: 'statement-history', group: 'statements', label: 'Statement History' },
  { id: 'review-categories', group: 'statements', label: 'Review Categories' },
  { id: 'financial-memory', group: 'statements', label: 'Financial Memory' },

  { id: 'budgets', group: 'planning', label: 'Budgets' },
  { id: 'goals', group: 'planning', label: 'Goals' },
  { id: 'investments', group: 'planning', label: 'Investments' },

  { id: 'insights', group: 'analysis', label: 'Insights' },
  { id: 'reports', group: 'analysis', label: 'Reports' },
  { id: 'advanced-reports', group: 'analysis', label: 'Advanced Reports' },
  { id: 'ask-fyn', group: 'analysis', label: 'Ask Fyn' },

  { id: 'profile', group: 'your-account', label: 'Profile' },
  { id: 'subscription', group: 'your-account', label: 'Subscription' },
  { id: 'referrals', group: 'your-account', label: 'Refer & Earn' },
  { id: 'settings', group: 'your-account', label: 'Settings' },
  { id: 'support', group: 'your-account', label: 'Support' },
] as const;

/**
 * The analytics allowlist is DERIVED here, never declared separately. Adding a destination above
 * makes it trackable in the same edit; there is no second list to forget, and therefore no way for
 * a destination to be navigable but silently unmeasured.
 */
export const NAV_DESTINATION_IDS: readonly string[] = NAV_TAXONOMY.map((e) => e.id);
