# Settings Redesign (Web) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single 1169-line scrolling `Settings.tsx` page with a grouped-list-plus-pane layout (macOS-Settings-style: fixed-width category nav + content pane), each category split into its own self-contained component, with HIG/hierarchy fixes folded in.

**Architecture:** `Settings.tsx` becomes a thin shell: reads/writes the `?tab=` query param, renders `SettingsNav` (fixed-width category list) beside the selected pane. Four panes (General, Security, Data, Account) share one identity fetch via a new `useAccountUser` hook owned by the shell, passed down as props. Three panes (Categorization, Connected Apps, Bank Sync) are fully self-contained — they already only ever needed their own independent API call, so they keep it. No backend changes; every pane calls the exact same endpoints the monolith already called.

**Tech Stack:** React 18, TypeScript, React Router v6 (`useSearchParams`), Tailwind, existing Fynora design-system components (`FinoraCard`, `SectionHeader`, `Button`, `IconButton`, `Skeleton`), Vitest + Testing Library.

**Spec:** No separate spec file — this is a bounded-path redesign (existing page, contained blast radius). The agreed design lives in this plan's Architecture section and the per-task notes below, which record the evidence (file:line citations) each decision is based on.

## Global Constraints

- No backend/API changes. Every pane calls an endpoint the monolith already called, unchanged.
- Sentence case on every button label — drop every `className="uppercase"` on a `<Button>`/`<IconButton>` inside Settings. (Apple HIG `settings.md`: system/app settings text should read as plain language, not shouting; also flagged independently by the `frontend-design` skill's AI-tell list.)
- ~~Delete Account button variant change~~ — reconsidered. `frontend/src/design-system/Button.tsx:8-11` shows web's `danger` variant is already outline-only (`border border-danger text-danger hover:bg-danger-bg`, no filled background) — it was never the "loud solid red button" the earlier design pass assumed, so it already satisfies the ui-refactor `hierarchy.md` tactic ("destructive-but-not-primary stays outline/tertiary weight"). No change needed; Task 9 is a plain verbatim extraction.
- Rename the "AI" section to "Categorization" everywhere in web copy (title, file name, route/tab key) to match mobile's existing name for the identical control (`mobile/src/screens/SettingsScreen.tsx:378`, "How confident a suggestion must be to apply on its own").
- Every pane's card shell is `FinoraCard` + `SectionHeader` (`frontend/src/design-system/FinoraCard.tsx`, `frontend/src/design-system/SectionHeader.tsx`), not the old `SectionCard` from `frontend/src/components/AccountUI.tsx`. Settings and Profile are currently the only two pages still on the old shell (confirmed via `grep -rln "SectionCard" frontend/src/pages`); this plan closes that gap for Settings. Profile is untouched — out of scope.
- `SettingsNav`'s active-row treatment reuses `bg-primary/10 text-primary` (the icon-chip pairing already used in `About.tsx`, `Careers.tsx`, `AdvancedReports.tsx`), not `Sidebar.tsx`'s `fixed-*` tokens — those are locked to the permanently-dark outer app sidebar and are wrong on a light/dark-aware content-area nav.
- Every pane's inner content stays capped at `max-w-2xl` (not stretched to fill the new wider right column) — ui-refactor `layout-spacing.md`, "shrink the canvas."

---

## File Structure

```
frontend/src/pages/settings/
  useAccountUser.ts       # new — shared identity fetch/update hook (General/Security/Data/Account)
  SettingsNav.tsx          # new — fixed-width category list, active-tab styling
  GeneralPane.tsx           # new — low balance / timezone / theme / retake tour
  SecurityPane.tsx          # new — password / phone verification / active sessions
  CategorizationPane.tsx    # new — confidence threshold (was "AI" section)
  DataPane.tsx               # new — import stats + export data
  ConnectedAppsPane.tsx      # new — Gmail connection (verbatim move)
  BankSyncPane.tsx           # new — Account Aggregator links (verbatim move)
  AccountPane.tsx             # new — deactivate / delete account

frontend/src/pages/Settings.tsx      # rewritten — shell only, ~90 lines
frontend/src/pages/Settings.test.tsx # rewritten — shell-level tests only (tab switching, nav)
frontend/src/pages/settings/*.test.tsx # new — one test file per pane, assertions moved from the old Settings.test.tsx
```

No other files change. `App.tsx`'s three route lines (`/app/settings`, `/app/settings/gmail/review`, `/app/settings/bank-sync/:linkId/confirm`) are untouched — the tab selection lives in a query param on the existing `/app/settings` route, not a new route.

---

## Task 1: `useAccountUser` shared hook

**Files:**
- Create: `frontend/src/pages/settings/useAccountUser.ts`
- Test: covered indirectly by Task 2 and Task 3's pane tests (this hook has no UI of its own — testing it only through a consuming component matches this codebase's existing convention, e.g. `useDelayedLoading` has no standalone test file either).

**Interfaces:**
- Produces: `useAccountUser(): { user: AccountUserState | null; loading: boolean; loadError: boolean; setUser: (u: AccountUserState) => void }` where `AccountUserState = { phoneNumber: string; phoneVerified: boolean; passwordChangedAt: string | null; signInMethod: 'PASSWORD' | 'GOOGLE'; lowBalanceThreshold: number; timezone: string }`.
- Consumes: `userApi.get()` from `frontend/src/api/endpoints.ts` (unchanged).

- [ ] **Step 1: Write the hook**

```ts
// frontend/src/pages/settings/useAccountUser.ts
import { useEffect, useState } from 'react';
import { userApi } from '../../api/endpoints';

export interface AccountUserState {
  phoneNumber: string;
  phoneVerified: boolean;
  passwordChangedAt: string | null;
  signInMethod: 'PASSWORD' | 'GOOGLE';
  lowBalanceThreshold: number;
  timezone: string;
}

/**
 * The one userApi.get() call General, Security, Data, and Account all need a slice of --
 * fetched once here instead of once per pane. Categorization/Connected Apps/Bank Sync each only
 * ever needed their own independent endpoint, so they keep fetching for themselves inside their
 * own pane component; this hook exists only for the genuinely shared slice.
 */
export function useAccountUser() {
  const [user, setUser] = useState<AccountUserState | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    userApi.get().then((u) => {
      setUser({
        // '' not null: a Google Sign-In account has no phone number on file at all -- see
        // AuthService.createGoogleUserRecord's own doc comment on the backend.
        phoneNumber: u.phoneNumber ?? '',
        phoneVerified: u.phoneVerified,
        passwordChangedAt: u.passwordChangedAt,
        signInMethod: u.signInMethod,
        lowBalanceThreshold: u.lowBalanceThreshold,
        timezone: u.timezone,
      });
      setLoading(false);
    }).catch(() => {
      setLoadError(true);
      setLoading(false);
    });
  }, []);

  return { user, loading, loadError, setUser };
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/settings/useAccountUser.ts
git commit -m "feat(settings): extract shared account-identity hook"
```

---

## Task 2: `SettingsNav` component

**Files:**
- Create: `frontend/src/pages/settings/SettingsNav.tsx`
- Test: `frontend/src/pages/settings/SettingsNav.test.tsx`

**Interfaces:**
- Consumes: nothing external.
- Produces: `export const SETTINGS_CATEGORIES: { key: string; label: string; icon: ReactNode }[]` (used by `Settings.tsx` to look up the active pane) and `export function SettingsNav({ active, onSelect }: { active: string; onSelect: (key: string) => void })`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/settings/SettingsNav.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SettingsNav, SETTINGS_CATEGORIES } from './SettingsNav';

describe('SettingsNav', () => {
  it('renders every category with sentence-case labels', () => {
    render(<SettingsNav active="general" onSelect={vi.fn()} />);
    for (const c of SETTINGS_CATEGORIES) {
      expect(screen.getByRole('button', { name: c.label })).toBeInTheDocument();
      expect(c.label).not.toBe(c.label.toUpperCase());
    }
  });

  it('marks the active category and calls onSelect with the tapped key', async () => {
    const onSelect = vi.fn();
    render(<SettingsNav active="security" onSelect={onSelect} />);
    expect(screen.getByRole('button', { name: 'Security' })).toHaveAttribute('aria-current', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Data' }));
    expect(onSelect).toHaveBeenCalledWith('data');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/settings/SettingsNav.test.tsx`
Expected: FAIL with "Failed to resolve import './SettingsNav'"

- [ ] **Step 3: Write minimal implementation**

```tsx
// frontend/src/pages/settings/SettingsNav.tsx
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/settings/SettingsNav.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/SettingsNav.tsx frontend/src/pages/settings/SettingsNav.test.tsx
git commit -m "feat(settings): add category nav list component"
```

---

## Task 3: `GeneralPane`

**Files:**
- Create: `frontend/src/pages/settings/GeneralPane.tsx` — move the General `SectionCard` block from `frontend/src/pages/Settings.tsx:560-629` (fields, save button, retake tour) plus `GeneralSkeletonFields` (`Settings.tsx:1052-1070`) and the `availableTimezones()` helper (`Settings.tsx:43-58`).
- Create: `frontend/src/pages/settings/GeneralPane.test.tsx` — move the `describe('General'` / preferences-save / retake-tour blocks from `frontend/src/pages/Settings.test.tsx`.

**Interfaces:**
- Consumes: `AccountUserState` and `setUser` from Task 1's `useAccountUser`; `useTheme()` from `frontend/src/context/ThemeContext` (unchanged, self-contained); `useAuth().setOnboardingCompleted` from `frontend/src/context/AuthContext` (unchanged); `onboardingApi`, `userApi` from `frontend/src/api/endpoints`; `parsePositiveAmount` from `frontend/src/lib/validation`; `FinoraCard`, `SectionHeader`, `Skeleton`, `Button` from the design system; `SaveStatus` from `frontend/src/components/AccountUI`.
- Produces: `export function GeneralPane({ user, loading, loadError, onUserUpdate }: { user: AccountUserState | null; loading: boolean; loadError: boolean; onUserUpdate: (u: AccountUserState) => void })`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/settings/GeneralPane.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '../../context/ThemeContext';
import { AuthProvider } from '../../context/AuthContext';
import { GeneralPane } from './GeneralPane';
import { userApi, onboardingApi, authApi } from '../../api/endpoints';
import type { AccountUserState } from './useAccountUser';

vi.mock('../../api/endpoints', () => ({
  userApi: { update: vi.fn() },
  onboardingApi: { reset: vi.fn().mockResolvedValue(undefined) },
  authApi: { refresh: vi.fn().mockRejectedValue(new Error('no session')) },
}));

function user(overrides: Partial<AccountUserState> = {}): AccountUserState {
  return {
    phoneNumber: '', phoneVerified: false, passwordChangedAt: null, signInMethod: 'PASSWORD',
    lowBalanceThreshold: 2000, timezone: 'Asia/Kolkata', ...overrides,
  };
}

function renderPane(props: Partial<React.ComponentProps<typeof GeneralPane>> = {}) {
  const onUserUpdate = vi.fn();
  render(
    <AuthProvider>
      <ThemeProvider>
        <GeneralPane user={user()} loading={false} loadError={false} onUserUpdate={onUserUpdate} {...props} />
      </ThemeProvider>
    </AuthProvider>
  );
  return { onUserUpdate };
}

describe('GeneralPane', () => {
  it('saves the low balance threshold and calls onUserUpdate with the server response', async () => {
    vi.mocked(userApi.update).mockResolvedValue({ lowBalanceThreshold: 5000, timezone: 'Asia/Kolkata' } as never);
    const { onUserUpdate } = renderPane();
    await userEvent.clear(screen.getByLabelText(/low balance alert/i));
    await userEvent.type(screen.getByLabelText(/low balance alert/i), '5000');
    await userEvent.click(screen.getByRole('button', { name: 'Save preferences' }));
    await waitFor(() => expect(onUserUpdate).toHaveBeenCalledWith(expect.objectContaining({ lowBalanceThreshold: 5000 })));
  });

  it('every button label is sentence case, not all caps', () => {
    renderPane();
    for (const btn of screen.getAllByRole('button')) {
      expect(btn.textContent).not.toBe(btn.textContent?.toUpperCase());
    }
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/settings/GeneralPane.test.tsx`
Expected: FAIL with "Failed to resolve import './GeneralPane'"

- [ ] **Step 3: Write the implementation**

Move `availableTimezones()` (`Settings.tsx:43-58`) verbatim to the top of the new file. Move the JSX inside the General `<SectionCard>` (`Settings.tsx:560-629`) into the new component, swapping `SectionCard` for `FinoraCard`+`SectionHeader`, replacing every `lowBalanceThreshold`/`savedLowBalanceThreshold`/`timezone`/`savedTimezone` local `useState` with the draft-overlay pattern already proven on mobile (`mobile/src/screens/SettingsScreen.tsx:109-111`, `savedX ?? draftX`) layered over the `user` prop, and dropping every `className="uppercase"`:

```tsx
// frontend/src/pages/settings/GeneralPane.tsx
import { useRef, useState } from 'react';
import { SlidersHorizontal } from 'lucide-react';
import { userApi, onboardingApi } from '../../api/endpoints';
import { useTheme } from '../../context/ThemeContext';
import { useAuth } from '../../context/AuthContext';
import { parsePositiveAmount } from '../../lib/validation';
import { SaveStatus } from '../../components/AccountUI';
import { Skeleton } from '../../design-system/Skeleton';
import { Button } from '../../design-system/Button';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';
import type { AccountUserState } from './useAccountUser';

function availableTimezones(): string[] {
  try {
    // @ts-expect-error -- not in the TS lib.d.ts on every configured target yet.
    const values = Intl.supportedValuesOf?.('timeZone');
    if (Array.isArray(values) && values.length > 0) return values;
  } catch {
    // fall through to the curated list below
  }
  return [
    'Asia/Kolkata', 'UTC', 'America/New_York', 'America/Chicago', 'America/Denver',
    'America/Los_Angeles', 'Europe/London', 'Europe/Paris', 'Europe/Berlin', 'Asia/Dubai',
    'Asia/Singapore', 'Asia/Tokyo', 'Asia/Shanghai', 'Australia/Sydney',
  ];
}

export function GeneralPane({
  user, loading, loadError, onUserUpdate,
}: {
  user: AccountUserState | null; loading: boolean; loadError: boolean;
  onUserUpdate: (u: AccountUserState) => void;
}) {
  const { theme, setTheme } = useTheme();
  const { setOnboardingCompleted } = useAuth();
  const [timezones] = useState<string[]>(availableTimezones);

  const [lowBalanceDraft, setLowBalanceDraft] = useState<string | null>(null);
  const [timezoneDraft, setTimezoneDraft] = useState<string | null>(null);
  const lowBalanceThreshold = lowBalanceDraft ?? String(user?.lowBalanceThreshold ?? '2000');
  const timezone = timezoneDraft ?? user?.timezone ?? 'Asia/Kolkata';
  const prefsDirty = lowBalanceDraft !== null || timezoneDraft !== null;

  const [prefsSaving, setPrefsSaving] = useState(false);
  const [prefsJustSaved, setPrefsJustSaved] = useState(false);
  const [prefsError, setPrefsError] = useState(false);
  const [prefsInvalid, setPrefsInvalid] = useState<string | null>(null);
  const prefsJustSavedTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  const [retakingTour, setRetakingTour] = useState(false);
  const [retakeTourError, setRetakeTourError] = useState<string | null>(null);

  async function savePreferences() {
    const threshold = parsePositiveAmount(lowBalanceThreshold);
    if (threshold === null) {
      setPrefsInvalid('Low balance alert must be a number greater than zero.');
      return;
    }
    setPrefsInvalid(null);
    setPrefsSaving(true);
    setPrefsError(false);
    try {
      const saved = await userApi.update({ lowBalanceThreshold: threshold, timezone });
      onUserUpdate({ ...(user as AccountUserState), lowBalanceThreshold: saved.lowBalanceThreshold, timezone: saved.timezone });
      setLowBalanceDraft(null);
      setTimezoneDraft(null);
      setPrefsJustSaved(true);
      if (prefsJustSavedTimeout.current) clearTimeout(prefsJustSavedTimeout.current);
      prefsJustSavedTimeout.current = setTimeout(() => setPrefsJustSaved(false), 2000);
    } catch {
      setPrefsError(true);
    } finally {
      setPrefsSaving(false);
    }
  }

  async function retakeTour() {
    setRetakingTour(true);
    setRetakeTourError(null);
    try {
      await onboardingApi.reset();
      setOnboardingCompleted(false);
    } catch (err) {
      const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message;
      setRetakeTourError(message || "Couldn't restart the tour -- please try again.");
    } finally {
      setRetakingTour(false);
    }
  }

  return (
    <FinoraCard>
      <SectionHeader title="General" />
      <p className="text-sm text-muted -mt-3 mb-5">Customize your Fynora experience</p>
      {loading ? (
        <Skeleton.Region label="Loading your preferences">
          <GeneralSkeletonFields />
        </Skeleton.Region>
      ) : loadError ? (
        <p className="text-muted text-sm">Couldn't load your settings — please try again later.</p>
      ) : (
        <>
          <div className="grid md:grid-cols-3 gap-4">
            <div>
              <label htmlFor="settings-low-balance-threshold" className="block text-xs uppercase text-muted mb-1">Low balance alert</label>
              <input
                id="settings-low-balance-threshold"
                type="number"
                min="1"
                step="1"
                value={lowBalanceThreshold}
                onChange={(e) => { setLowBalanceDraft(e.target.value); setPrefsInvalid(null); }}
                className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label htmlFor="settings-timezone" className="block text-xs uppercase text-muted mb-1">Timezone</label>
              <select
                id="settings-timezone"
                value={timezone}
                onChange={(e) => setTimezoneDraft(e.target.value)}
                className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
              >
                {!timezones.includes(timezone) && <option value={timezone}>{timezone}</option>}
                {timezones.map((tz) => <option key={tz} value={tz}>{tz}</option>)}
              </select>
            </div>
            <div>
              <label htmlFor="settings-theme" className="block text-xs uppercase text-muted mb-1">Theme</label>
              <select
                id="settings-theme"
                value={theme}
                onChange={(e) => setTheme(e.target.value as 'light' | 'dark' | 'system')}
                className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
              >
                <option value="light">Light</option>
                <option value="dark">Dark</option>
                <option value="system">System</option>
              </select>
            </div>
          </div>
          <div className="flex items-center justify-between mt-5 pt-4 border-t border-border">
            <p className="text-xs text-muted">Theme applies instantly. Low balance alert and timezone save when you click Save.</p>
            <div className="flex items-center gap-3">
              <SaveStatus dirty={prefsDirty} saving={prefsSaving} justSaved={prefsJustSaved} error={prefsError} errorMessage={prefsInvalid} />
              <Button onClick={savePreferences} disabled={!prefsDirty} loading={prefsSaving}>Save preferences</Button>
            </div>
          </div>
          <div className="flex items-center justify-between mt-4 pt-4 border-t border-border">
            <div>
              <p className="text-sm font-medium text-ink">Retake Product Tour</p>
              <p className="text-xs text-muted">Replay the onboarding experience anytime.</p>
              {retakeTourError ? <p className="text-xs text-danger mt-1">{retakeTourError}</p> : null}
            </div>
            <Button variant="secondary" onClick={retakeTour} loading={retakingTour}>Retake Tour</Button>
          </div>
        </>
      )}
    </FinoraCard>
  );
}

function GeneralSkeletonFields() {
  return (
    <>
      <div className="grid md:grid-cols-3 gap-4">
        {[0, 1, 2].map((i) => (
          <div key={i} className="space-y-1.5">
            <Skeleton.Text width="w-24" className="h-2.5" />
            <Skeleton.Block className="h-9 w-full" />
          </div>
        ))}
      </div>
      <div className="flex items-center justify-between mt-5 pt-4 border-t border-border">
        <Skeleton.Text width="w-72" className="h-2.5" />
        <Skeleton.Block className="h-8 w-32" />
      </div>
    </>
  );
}
```

Note the `SlidersHorizontal` import is unused in this file body above (the icon now lives in `SettingsNav.tsx`'s category list, not on the pane itself, since `FinoraCard`/`SectionHeader` don't take an icon prop) — delete that import when implementing; left in the sketch above only to flag it, don't ship it.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/settings/GeneralPane.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/GeneralPane.tsx frontend/src/pages/settings/GeneralPane.test.tsx
git commit -m "feat(settings): extract General pane, adopt draft-overlay state and FinoraCard"
```

---

## Task 4: `SecurityPane`

**Files:**
- Create: `frontend/src/pages/settings/SecurityPane.tsx` — move the Security `SectionCard` block (`Settings.tsx:631-714`), `SecurityBasicsSkeletonFields` (`Settings.tsx:1074-1091`), `ActiveSessionsSkeletonFields` (`Settings.tsx:1094-1111`), `deviceLabel()` (`Settings.tsx:60-65`), `expiresInLabel()` (`Settings.tsx:79-89`), and the `ChangePasswordModal` open/close wiring (`Settings.tsx:1023-1029`).
- Create: `frontend/src/pages/settings/SecurityPane.test.tsx` — move the Security/Active-Sessions `describe` blocks from `Settings.test.tsx`.

**Interfaces:**
- Consumes: `AccountUserState` (Task 1); `deviceApi`, `formatRelativeTime`, `maskPhone` (unchanged); `ChangePasswordModal` from `frontend/src/components/ChangePasswordModal` (unchanged); `VerifiedBadge` from `AccountUI`.
- Produces: `export function SecurityPane({ user, loading, loadError, onUserUpdate }: { user: AccountUserState | null; loading: boolean; loadError: boolean; onUserUpdate: (u: AccountUserState) => void })`. `onUserUpdate` is used only to patch `passwordChangedAt` after a successful change, exactly as the monolith's `ChangePasswordModal onSuccess` callback already did.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/settings/SecurityPane.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SecurityPane } from './SecurityPane';
import { deviceApi } from '../../api/endpoints';
import type { AccountUserState } from './useAccountUser';

vi.mock('../../api/endpoints', () => ({
  deviceApi: { list: vi.fn(), revoke: vi.fn() },
  passwordChangeApi: { start: vi.fn(), verifyOtp: vi.fn(), complete: vi.fn() },
}));

function user(overrides: Partial<AccountUserState> = {}): AccountUserState {
  return {
    phoneNumber: '+919876543210', // synthetic-ok: invented test number
    phoneVerified: true, passwordChangedAt: null, signInMethod: 'PASSWORD',
    lowBalanceThreshold: 2000, timezone: 'Asia/Kolkata', ...overrides,
  };
}

describe('SecurityPane', () => {
  it('shows the active session and lets the user sign it out', async () => {
    vi.mocked(deviceApi.list).mockResolvedValue([
      { id: 's1', current: false, browser: 'Chrome', device: 'macOS', lastSeenAt: null, lastSeenIp: null, sessionStartedAt: null, sessionExpiresAt: null },
    ] as never);
    vi.mocked(deviceApi.revoke).mockResolvedValue(undefined as never);
    render(<SecurityPane user={user()} loading={false} loadError={false} onUserUpdate={vi.fn()} />);
    await screen.findByText('Chrome on macOS');
    await userEvent.click(screen.getByRole('button', { name: 'Sign out this device' }));
    await waitFor(() => expect(deviceApi.revoke).toHaveBeenCalledWith('s1'));
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/settings/SecurityPane.test.tsx`
Expected: FAIL with "Failed to resolve import './SecurityPane'"

- [ ] **Step 3: Write the implementation**

Move `deviceLabel()`, `expiresInLabel()` verbatim to the top of the file. Move the two `SectionCard` bodies from `Settings.tsx:640-713` into one `FinoraCard`, swap `SectionCard`→`FinoraCard`+`SectionHeader`, wire `passwordChangedAt`/`phoneNumber`/`phoneVerified`/`signInMethod` from the `user` prop instead of local state, keep the `sessions`/`sessionsLoading`/`sessionsError`/`revokingId`/`loadSessions()` block (`Settings.tsx:232-235, 293-300, 501-512`) exactly as-is (it's already fully self-contained), and keep `ChangePasswordModal`'s render block (`Settings.tsx:1023-1029`) with its `onSuccess` now calling `onUserUpdate({ ...user, passwordChangedAt: new Date().toISOString() })`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/settings/SecurityPane.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/SecurityPane.tsx frontend/src/pages/settings/SecurityPane.test.tsx
git commit -m "feat(settings): extract Security pane"
```

---

## Task 5: `CategorizationPane`

**Files:**
- Create: `frontend/src/pages/settings/CategorizationPane.tsx` — move the "AI" `SectionCard` block (`Settings.tsx:716-748`) and `AISkeletonFields` (`Settings.tsx:1114-1125`), verbatim except the title changes from `"AI"` to `"Categorization"` and the icon from `<Sparkles>` to whatever `SectionHeader` needs (none — icon now lives only in `SettingsNav`).
- Create: `frontend/src/pages/settings/CategorizationPane.test.tsx` — move the confidence-threshold `describe` block from `Settings.test.tsx`.

**Interfaces:**
- Consumes: `workspaceApi` (unchanged) — fully self-contained, no props needed.
- Produces: `export function CategorizationPane()`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/settings/CategorizationPane.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategorizationPane } from './CategorizationPane';
import { workspaceApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  workspaceApi: { getSettings: vi.fn(), updateSettings: vi.fn() },
}));

describe('CategorizationPane', () => {
  it('loads the confidence threshold and saves a change', async () => {
    vi.mocked(workspaceApi.getSettings).mockResolvedValue({ autoApplyConfidenceThreshold: 90 } as never);
    vi.mocked(workspaceApi.updateSettings).mockResolvedValue({ autoApplyConfidenceThreshold: 75 } as never);
    render(<CategorizationPane />);
    const slider = await screen.findByLabelText(/confidence threshold/i);
    expect(slider).toHaveValue('90');
    await userEvent.click(screen.getByRole('button', { name: 'Save setting' }));
    // disabled while clean -- change the slider first via fireEvent in the real test file, then assert the call
    await waitFor(() => expect(screen.getByText('Categorization')).toBeInTheDocument());
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/settings/CategorizationPane.test.tsx`
Expected: FAIL with "Failed to resolve import './CategorizationPane'"

- [ ] **Step 3: Write the implementation**

Move `Settings.tsx:716-748` verbatim into the new file (state block `confidenceThreshold`/`savedConfidenceThreshold`/`intelLoading`/`intelSaving`/`intelJustSaved`/`intelError` already lives at `Settings.tsx:222-227`, `saveIntelligencePreferences()` at `Settings.tsx:484-499`, `intelDirty` at `Settings.tsx:268`, and the `useEffect` fetch at `Settings.tsx:425-429` — move all of these together, unchanged). Title becomes `"Categorization"`; subtitle text unchanged ("Control how Fynora reviews and understands your financial documents").

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/settings/CategorizationPane.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/CategorizationPane.tsx frontend/src/pages/settings/CategorizationPane.test.tsx
git commit -m "feat(settings): extract Categorization pane (renamed from AI)"
```

---

## Task 6: `DataPane`

**Files:**
- Create: `frontend/src/pages/settings/DataPane.tsx` — move the Data `SectionCard` block (`Settings.tsx:750-779`), including `ExportDataModal` wiring (`Settings.tsx:1047`).
- Create: `frontend/src/pages/settings/DataPane.test.tsx` — move the Data/export `describe` block from `Settings.test.tsx`.

**Interfaces:**
- Consumes: `analyticsApi` (self-contained fetch, unchanged); `AccountUserState.signInMethod`, `loading`, `loadError` (Task 1, only for the Export button's disabled/title state and the modal); `MetricTile`, `formatDayMonthYear` from `AccountUI`; `ExportDataModal`.
- Produces: `export function DataPane({ loading, loadError, signInMethod }: { loading: boolean; loadError: boolean; signInMethod: 'PASSWORD' | 'GOOGLE' })`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/settings/DataPane.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DataPane } from './DataPane';
import { analyticsApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  analyticsApi: { importStatistics: vi.fn() },
}));

describe('DataPane', () => {
  it('renders import statistics once loaded', async () => {
    vi.mocked(analyticsApi.importStatistics).mockResolvedValue({
      totalStatements: 12, totalTransactionsImported: 340, totalTransactionsSkipped: 2, lastImportedAt: '2026-08-01T00:00:00Z',
    } as never);
    render(<DataPane loading={false} loadError={false} signInMethod="PASSWORD" />);
    expect(await screen.findByText('12')).toBeInTheDocument();
  });

  it('disables Export My Data while the account is still loading', () => {
    render(<DataPane loading={true} loadError={false} signInMethod="PASSWORD" />);
    expect(screen.getByRole('button', { name: 'Export My Data' })).toBeDisabled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/settings/DataPane.test.tsx`
Expected: FAIL with "Failed to resolve import './DataPane'"

- [ ] **Step 3: Write the implementation**

Move `Settings.tsx:229-231` (`importStats`/`importStatsFailed` state), the fetch at `Settings.tsx:437`, and the JSX block `Settings.tsx:750-779` verbatim, plus `ExportDataModal` render (`Settings.tsx:1047`) gated on a local `exportOpen` state.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/settings/DataPane.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/DataPane.tsx frontend/src/pages/settings/DataPane.test.tsx
git commit -m "feat(settings): extract Data pane"
```

---

## Task 7: `ConnectedAppsPane`

**Files:**
- Create: `frontend/src/pages/settings/ConnectedAppsPane.tsx` — move the entire Gmail `SectionCard` block (`Settings.tsx:781-896`), `GmailSkeletonFields` (`Settings.tsx:1148-1159`), `GmailUpgradePrompt` (`Settings.tsx:1131-1146`), `gmailCallbackMessage()` (`Settings.tsx:95-104`), `gmailLastSyncedLabel()` (`Settings.tsx:106-109`), `SCOPE_LABELS`/`gmailPermissionLabels()` (`Settings.tsx:119-125`) — fully self-contained, verbatim.
- Create: `frontend/src/pages/settings/ConnectedAppsPane.test.tsx` — move the Gmail `describe` block from `Settings.test.tsx`.

**Interfaces:**
- Consumes: `gmailApi`, `PremiumFeatureGate`, `useSearchParams` (its own — reads and strips `?gmail=` independently of the shell's `?tab=`, using the same "copy searchParams, delete only the one key" pattern already at `Settings.tsx:443-447` so the `?tab=` param is never clobbered).
- Produces: `export function ConnectedAppsPane()`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/settings/ConnectedAppsPane.test.tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ConnectedAppsPane } from './ConnectedAppsPane';
import { gmailApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  gmailApi: { status: vi.fn(), connect: vi.fn(), disconnect: vi.fn(), syncNow: vi.fn() },
  entitlementsApi: { mine: vi.fn().mockResolvedValue({ planCode: 'FREE', features: {} }) },
}));

describe('ConnectedAppsPane', () => {
  it('shows the connected state with the account email', async () => {
    vi.mocked(gmailApi.status).mockResolvedValue({
      available: true, connected: true, needsReconnect: false, googleEmail: 'amy@example.com',
      grantedScopes: [], lastDiscoveryAt: null, transactionsFound: 3, needsReview: 1,
    } as never);
    render(<MemoryRouter><ConnectedAppsPane /></MemoryRouter>);
    expect(await screen.findByText('amy@example.com')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/settings/ConnectedAppsPane.test.tsx`
Expected: FAIL with "Failed to resolve import './ConnectedAppsPane'"

- [ ] **Step 3: Write the implementation**

Verbatim move per the Files note above — this section had zero dependency on `Settings.tsx`'s shared state already, so the extraction is a pure cut-and-paste plus updating the two relative imports (`../api/endpoints` → `../../api/endpoints`, etc.).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/settings/ConnectedAppsPane.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/ConnectedAppsPane.tsx frontend/src/pages/settings/ConnectedAppsPane.test.tsx
git commit -m "feat(settings): extract Connected Apps pane"
```

---

## Task 8: `BankSyncPane`

**Files:**
- Create: `frontend/src/pages/settings/BankSyncPane.tsx` — move the entire Bank Sync `SectionCard` block (`Settings.tsx:898-983`), `AaSkeletonFields` (`Settings.tsx:1162-1169`), `aaFiTypeLabel()`/`AA_FI_TYPE_LABELS` (`Settings.tsx:127-133`), `aaStatusCopy()` (`Settings.tsx:139-151`), `aaStatusBadgeClass()` (`Settings.tsx:153-162`), `AA_TERMINAL_STATUSES` (`Settings.tsx:168`) — fully self-contained, verbatim.
- Create: `frontend/src/pages/settings/BankSyncPane.test.tsx` — move the Bank Sync `describe` block from `Settings.test.tsx`.

**Interfaces:**
- Consumes: `accountAggregatorApi` (unchanged).
- Produces: `export function BankSyncPane()`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/settings/BankSyncPane.test.tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BankSyncPane } from './BankSyncPane';
import { accountAggregatorApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  accountAggregatorApi: { list: vi.fn(), initiate: vi.fn(), disconnect: vi.fn() },
}));

describe('BankSyncPane', () => {
  it('shows a linked account and its status', async () => {
    vi.mocked(accountAggregatorApi.list).mockResolvedValue([
      { id: 'l1', fiType: 'DEPOSIT', status: 'ACTIVE' },
    ] as never);
    render(<BankSyncPane />);
    expect(await screen.findByText('Bank Account')).toBeInTheDocument();
    expect(screen.getByText('Connected')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/settings/BankSyncPane.test.tsx`
Expected: FAIL with "Failed to resolve import './BankSyncPane'"

- [ ] **Step 3: Write the implementation**

Verbatim move, same reasoning as Task 7 — zero dependency on shared state.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/settings/BankSyncPane.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/BankSyncPane.tsx frontend/src/pages/settings/BankSyncPane.test.tsx
git commit -m "feat(settings): extract Bank Sync pane"
```

---

## Task 9: `AccountPane`

**Files:**
- Create: `frontend/src/pages/settings/AccountPane.tsx` — move the "Manage Your Account" `SectionCard` block (`Settings.tsx:985-1021`), `handleDeactivated()` (`Settings.tsx:541-544`), `handleDeleted()` (`Settings.tsx:548-551`), and the `DeactivateAccountModal`/`DeleteAccountModal` render blocks (`Settings.tsx:1031-1045`).
- Create: `frontend/src/pages/settings/AccountPane.test.tsx` — move the Deactivate/Delete `describe` blocks from `Settings.test.tsx`, plus one new test for the button-variant fix.

**Interfaces:**
- Consumes: `AccountUserState.signInMethod`, `loading`, `loadError` (Task 1); `authApi`, `clearSessionAndRedirect` from `frontend/src/api/client`; `DeactivateAccountModal`, `DeleteAccountModal`.
- Produces: `export function AccountPane({ loading, loadError, signInMethod }: { loading: boolean; loadError: boolean; signInMethod: 'PASSWORD' | 'GOOGLE' })`.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/settings/AccountPane.test.tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AccountPane } from './AccountPane';

describe('AccountPane', () => {
  it('renders both Deactivate and Delete as outline buttons, not filled primary/premium ones', () => {
    render(<AccountPane loading={false} loadError={false} signInMethod="PASSWORD" />);
    // Regression guard, not a behavior change -- Button.tsx's `danger`/`secondary` variants were
    // already outline-only (no filled background) before this extraction; this just proves the
    // move didn't accidentally pick up variant="primary" or "premium" along the way.
    for (const name of ['Deactivate Account', 'Delete Account']) {
      const btn = screen.getByRole('button', { name });
      expect(btn.className).not.toMatch(/bg-primary\b/);
      expect(btn.className).not.toMatch(/bg-premium/);
    }
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/settings/AccountPane.test.tsx`
Expected: FAIL with "Failed to resolve import './AccountPane'"

- [ ] **Step 3: Write the implementation**

Move `Settings.tsx:985-1021` (JSX), `Settings.tsx:541-544` (`handleDeactivated`), `Settings.tsx:548-551` (`handleDeleted`), and `Settings.tsx:1031-1045` (modal renders) verbatim — no variant change, per the Global Constraints note above.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/settings/AccountPane.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/settings/AccountPane.tsx frontend/src/pages/settings/AccountPane.test.tsx
git commit -m "feat(settings): extract Account pane"
```

---

## Task 10: Rewrite `Settings.tsx` shell, retire the monolith

**Files:**
- Modify: `frontend/src/pages/Settings.tsx` — replace entirely.
- Modify: `frontend/src/pages/Settings.test.tsx` — replace entirely (shell-level tests only: tab switching, default tab, responsive list/pane behavior).

**Interfaces:**
- Consumes: `useAccountUser` (Task 1), `SettingsNav`/`SETTINGS_CATEGORIES` (Task 2), all seven panes (Tasks 3-9).
- Produces: `export default function Settings()` (same default export the route in `App.tsx` already imports — no route change needed).

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/Settings.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Settings from './Settings';
import { userApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  userApi: { get: vi.fn().mockResolvedValue({
    phoneNumber: '', phoneVerified: false, passwordChangedAt: null, signInMethod: 'PASSWORD',
    lowBalanceThreshold: 2000, timezone: 'Asia/Kolkata',
  }) },
  workspaceApi: { getSettings: vi.fn().mockResolvedValue({ autoApplyConfidenceThreshold: 90 }) },
  analyticsApi: { importStatistics: vi.fn().mockResolvedValue(null) },
  deviceApi: { list: vi.fn().mockResolvedValue([]) },
  gmailApi: { status: vi.fn().mockResolvedValue({ available: false }) },
  accountAggregatorApi: { list: vi.fn().mockResolvedValue([]) },
  onboardingApi: { reset: vi.fn() },
  authApi: { refresh: vi.fn().mockRejectedValue(new Error('no session')) },
}));

describe('Settings shell', () => {
  it('defaults to the General pane and switches panes on nav click', async () => {
    render(<MemoryRouter initialEntries={['/app/settings']}><Settings /></MemoryRouter>);
    expect(await screen.findByText('Low balance alert')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Security' }));
    expect(await screen.findByText('Active Sessions')).toBeInTheDocument();
  });

  it('reads the initial pane from the ?tab= query param', async () => {
    render(<MemoryRouter initialEntries={['/app/settings?tab=data']}><Settings /></MemoryRouter>);
    expect(await screen.findByText('Statements Imported')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/Settings.test.tsx`
Expected: FAIL — old `Settings.tsx` still renders the full monolith, "Active Sessions" text exists but nav click behavior doesn't (no `SettingsNav` rendered yet).

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/pages/Settings.tsx
import { useSearchParams } from 'react-router-dom';
import { useAccountUser } from './settings/useAccountUser';
import { SettingsNav, SETTINGS_CATEGORIES } from './settings/SettingsNav';
import { GeneralPane } from './settings/GeneralPane';
import { SecurityPane } from './settings/SecurityPane';
import { CategorizationPane } from './settings/CategorizationPane';
import { DataPane } from './settings/DataPane';
import { ConnectedAppsPane } from './settings/ConnectedAppsPane';
import { BankSyncPane } from './settings/BankSyncPane';
import { AccountPane } from './settings/AccountPane';

const DEFAULT_TAB = 'general';

export default function Settings() {
  const [searchParams, setSearchParams] = useSearchParams();
  const rawTab = searchParams.get('tab');
  // Below md, an absent ?tab= means "show the list, nothing selected yet" -- at md and up it
  // means "show General in the pane column." Both are satisfied by keeping `tab` possibly-null
  // here and letting the two responsive branches below interpret it differently.
  const tab = rawTab && SETTINGS_CATEGORIES.some((c) => c.key === rawTab) ? rawTab : null;
  const { user, loading, loadError, setUser } = useAccountUser();

  function selectTab(key: string) {
    const next = new URLSearchParams(searchParams);
    next.set('tab', key);
    setSearchParams(next);
  }

  function backToList() {
    const next = new URLSearchParams(searchParams);
    next.delete('tab');
    setSearchParams(next);
  }

  const activePane = renderPane(tab ?? DEFAULT_TAB, { user, loading, loadError, setUser });

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-ink">Settings</h1>
        <p className="text-sm text-muted mt-1">Manage your preferences, security, and account data.</p>
      </div>

      {/* Desktop: nav + pane side by side, nav always visible, pane always shows something. */}
      <div className="hidden md:flex gap-8 items-start">
        <SettingsNav active={tab ?? DEFAULT_TAB} onSelect={selectTab} />
        <div className="flex-1 min-w-0 max-w-2xl">{activePane}</div>
      </div>

      {/* Mobile web: list-only until a category is tapped, then pane-only with a back control. */}
      <div className="md:hidden">
        {tab === null ? (
          <SettingsNav active="" onSelect={selectTab} />
        ) : (
          <div className="max-w-2xl">
            <button type="button" onClick={backToList} className="text-sm text-muted mb-4">‹ Settings</button>
            {activePane}
          </div>
        )}
      </div>
    </div>
  );
}

function renderPane(
  key: string,
  props: { user: ReturnType<typeof useAccountUser>['user']; loading: boolean; loadError: boolean; setUser: ReturnType<typeof useAccountUser>['setUser'] }
) {
  switch (key) {
    case 'security': return <SecurityPane user={props.user} loading={props.loading} loadError={props.loadError} onUserUpdate={props.setUser} />;
    case 'categorization': return <CategorizationPane />;
    case 'data': return <DataPane loading={props.loading} loadError={props.loadError} signInMethod={props.user?.signInMethod ?? 'PASSWORD'} />;
    case 'connected-apps': return <ConnectedAppsPane />;
    case 'bank-sync': return <BankSyncPane />;
    case 'account': return <AccountPane loading={props.loading} loadError={props.loadError} signInMethod={props.user?.signInMethod ?? 'PASSWORD'} />;
    default: return <GeneralPane user={props.user} loading={props.loading} loadError={props.loadError} onUserUpdate={props.setUser} />;
  }
}
```

Then delete every line from the old `Settings.tsx` that Tasks 1-9 already moved out — the file should end at roughly 90 lines.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/Settings.test.tsx`
Expected: PASS

- [ ] **Step 5: Run the full pages/settings test suite together**

Run: `cd frontend && npx vitest run src/pages/settings src/pages/Settings.test.tsx`
Expected: all PASS — this is the point where a leftover unmoved bit of state, a stale import, or a prop name mismatch between the shell and a pane would surface.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/Settings.tsx frontend/src/pages/Settings.test.tsx
git commit -m "refactor(settings): replace monolith with nav+pane shell"
```

---

## Task 11: Manual verification

- [ ] **Step 1:** `cd frontend && npm run lint && npm run typecheck` (or the project's equivalent scripts — check `frontend/package.json` for the exact names) — zero errors on the whole `src/pages/settings/` tree and the modified `Settings.tsx`.
- [ ] **Step 2:** `cd frontend && npx vitest run` — full suite green, not just the settings files (a shared component like `Button` or `FinoraCard` could regress something on another page).
- [ ] **Step 3:** Start the dev server (`npm run dev` from `frontend/`), open `/app/settings` in the browser preview. Click through all 7 categories, confirm each pane's data loads, confirm the `?tab=` URL updates and a page refresh on `?tab=bank-sync` lands directly on Bank Sync. Resize to a narrow width and confirm the list-then-pane collapse behaves (list only when no tab selected, back arrow returns to the list).
- [ ] **Step 4:** Confirm no button anywhere in Settings is visually all-caps.
- [ ] **Step 5:** Re-read the full diff once more for anything the per-task steps didn't catch (stray unused imports, a skipped `TODO`, a console warning in the dev server log) — per this repo's standing verification rule, "nothing I can find is broken" is the bar, not "the new tests pass."
