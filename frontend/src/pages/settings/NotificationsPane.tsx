import { useEffect, useState } from 'react';
import { notificationPreferencesApi, type NotificationPreferenceDto } from '../../api/endpoints';
import { Skeleton } from '../../design-system/Skeleton';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';

const CHANNEL_COPY: Record<NotificationPreferenceDto['channel'], { label: string; description: string }> = {
  EMAIL: {
    label: 'Email',
    description: 'Statement and referral updates by email: a statement is ready, being checked or resolved, and referral rewards.',
  },
  PUSH: {
    label: 'Push notifications',
    description: 'The same updates as notifications on your phone, from the Fynora mobile app.',
  },
};

/**
 * The switch every FINANCIAL email's "Turn them off in your notification settings" line points to
 * (/app/settings?tab=notifications). Security messages -- sign-in codes, password changes, account
 * changes -- are always sent and are not listed, because the backend forces them on.
 */
export function NotificationsPane() {
  const [prefs, setPrefs] = useState<NotificationPreferenceDto[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [saving, setSaving] = useState<NotificationPreferenceDto['channel'] | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    notificationPreferencesApi.list().then(setPrefs).catch(() => setLoadError(true));
  }, []);

  async function toggle(pref: NotificationPreferenceDto) {
    setSaving(pref.channel);
    setSaveError(null);
    try {
      setPrefs(await notificationPreferencesApi.set(pref.channel, !pref.enabled));
    } catch {
      setSaveError("Couldn't save that change. Please try again.");
    } finally {
      setSaving(null);
    }
  }

  return (
    <FinoraCard>
      <SectionHeader title="Notifications" />
      {loadError ? (
        <p className="text-xs text-danger">Couldn't load your notification settings — please try again later.</p>
      ) : prefs === null ? (
        <Skeleton.Region label="Loading your notification settings">
          <Skeleton.Row className="py-3" />
          <Skeleton.Row className="py-3" />
        </Skeleton.Region>
      ) : (
        <>
          {prefs.map((pref) => {
            const copy = CHANNEL_COPY[pref.channel];
            return (
              <div key={pref.channel} className="flex items-center justify-between gap-4 py-3 border-b border-border">
                <div>
                  <p className="text-sm text-ink font-medium">{copy.label}</p>
                  <p className="text-xs text-muted mt-0.5">{copy.description}</p>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={pref.enabled}
                  aria-label={copy.label}
                  disabled={saving !== null}
                  onClick={() => void toggle(pref)}
                  className={`w-11 h-6 rounded-full transition-colors relative flex-shrink-0 disabled:opacity-40 ${pref.enabled ? 'bg-primary' : 'bg-border'}`}
                >
                  <span className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white shadow-card transition-transform ${pref.enabled ? 'translate-x-5' : ''}`} />
                </button>
              </div>
            );
          })}
          {saveError && <p role="alert" className="text-xs text-danger mt-3">{saveError}</p>}
          <p className="text-xs text-muted mt-3">
            Security messages — sign-in codes, password changes and account changes — are always sent, so you
            can't miss something that affects your account.
          </p>
        </>
      )}
    </FinoraCard>
  );
}
