import { useRef, useState } from 'react';
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

// Falls back to a curated list of common zones on browsers that predate
// Intl.supportedValuesOf (Safari < 15, older WebViews) rather than leaving the dropdown empty.
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

  // Draft-overlay pattern (same as mobile's SettingsScreen.tsx): null means "nothing typed yet,
  // follow the account," anything else is the user's in-progress edit.
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
    // Validate before sending -- a cleared `type="number"` field yields '', and
    // parsePositiveAmount('') is null, which would silently no-op the request.
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
