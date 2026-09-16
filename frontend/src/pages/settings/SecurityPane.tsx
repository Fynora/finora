import { useEffect, useState } from 'react';
import { Smartphone, X } from 'lucide-react';
import { deviceApi, type DeviceSession } from '../../api/endpoints';
import { ChangePasswordModal } from '../../components/ChangePasswordModal';
import { maskPhone } from '../../lib/maskPhone';
import { formatRelativeTime, VerifiedBadge } from '../../components/AccountUI';
import { Skeleton } from '../../design-system/Skeleton';
import { Button } from '../../design-system/Button';
import { IconButton } from '../../design-system/IconButton';
import { FinoraCard } from '../../design-system/FinoraCard';
import { SectionHeader } from '../../design-system/SectionHeader';
import type { AccountUserState } from './useAccountUser';

/** "Chrome on Windows" -- browser/device are both nullable, so this degrades gracefully either way. */
function deviceLabel(session: DeviceSession): string {
  if (session.browser && session.device) return `${session.browser} on ${session.device}`;
  return session.browser || session.device || 'Unknown device';
}

/**
 * "Expires in 2 days" for the absolute session cap. Null when the backend reports no cap, rather
 * than inventing a date.
 */
function expiresInLabel(sessionExpiresAt: string | null): string | null {
  if (!sessionExpiresAt) return null;
  const ms = new Date(sessionExpiresAt).getTime() - Date.now();
  if (Number.isNaN(ms)) return null;
  if (ms <= 0) return 'Expires shortly';
  const hours = Math.floor(ms / 3_600_000);
  if (hours < 1) return 'Expires within the hour';
  if (hours < 24) return `Expires in ${hours} hour${hours === 1 ? '' : 's'}`;
  const days = Math.round(hours / 24);
  return `Expires in ${days} day${days === 1 ? '' : 's'}`;
}

export function SecurityPane({
  user, loading, loadError, onUserUpdate,
}: {
  user: AccountUserState | null; loading: boolean; loadError: boolean;
  onUserUpdate: (u: AccountUserState) => void;
}) {
  const [changePasswordOpen, setChangePasswordOpen] = useState(false);
  const [sessions, setSessions] = useState<DeviceSession[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionsError, setSessionsError] = useState(false);
  const [revokingId, setRevokingId] = useState<string | null>(null);

  useEffect(() => {
    deviceApi.list()
      .then(setSessions)
      .catch(() => setSessionsError(true))
      .finally(() => setSessionsLoading(false));
  }, []);

  async function revokeSession(id: string) {
    setRevokingId(id);
    try {
      await deviceApi.revoke(id);
      setSessions((prev) => prev.filter((s) => s.id !== id));
    } catch {
      // Best-effort UI -- the list simply keeps the row and the user can retry.
    } finally {
      setRevokingId(null);
    }
  }

  return (
    <FinoraCard>
      <SectionHeader title="Security" />
      <p className="text-sm text-muted -mt-3 mb-5">Manage your password, verification, and active sessions</p>
      {loading ? (
        <Skeleton.Region label="Loading your security settings">
          <SecurityBasicsSkeletonFields />
        </Skeleton.Region>
      ) : loadError || !user ? (
        <p className="text-muted text-sm py-3">Couldn't load your settings — please try again later.</p>
      ) : (
        <>
          <div className="border-b border-border py-3 text-sm">
            <p className="text-ink font-medium">Password</p>
            <p className="text-muted text-xs mt-0.5">
              {formatRelativeTime(user.passwordChangedAt) ? `Last changed ${formatRelativeTime(user.passwordChangedAt)}` : 'Never changed'}
            </p>
            <p className="text-muted text-2xs mt-1">Keep your account secure by using a unique password.</p>
            <Button variant="secondary" size="sm" className="mt-3" onClick={() => setChangePasswordOpen(true)}>
              Change Password
            </Button>
          </div>
          <div className="flex items-center justify-between border-b border-border py-3 text-sm">
            <div>
              <p className="text-ink font-medium">Phone verification</p>
              <p className="text-muted text-xs">{user.phoneNumber ? maskPhone(user.phoneNumber) : 'No phone number on file'}</p>
            </div>
            {user.phoneVerified ? <VerifiedBadge /> : <span className="text-xs text-muted flex-shrink-0">Not verified</span>}
          </div>
        </>
      )}

      <div className="pt-3">
        <p className="text-ink font-medium text-sm">Active Sessions</p>
        <p className="text-muted text-2xs mt-0.5 mb-3">
          Every device currently signed in to your account. Signing one out here ends that session
          the next time it needs to refresh. Sessions also end on their own — after 24 hours of
          inactivity, or 30 days after signing in, whichever comes first.
        </p>
        {sessionsLoading ? (
          <Skeleton.Region label="Loading your active sessions">
            <ActiveSessionsSkeletonFields />
          </Skeleton.Region>
        ) : sessionsError ? (
          <p className="text-xs text-danger">Couldn't load your active sessions — please try again later.</p>
        ) : sessions.length === 0 ? (
          <p className="text-xs text-muted italic">No active sessions found.</p>
        ) : (
          <div className="space-y-2">
            {sessions.map((s) => (
              <div key={s.id} className="flex items-center justify-between gap-3 border border-border rounded-lg px-3 py-2.5">
                <div className="flex items-center gap-2.5 min-w-0">
                  <Smartphone size={15} className="text-muted flex-shrink-0" />
                  <div className="min-w-0">
                    <p className="text-sm text-ink truncate">
                      {deviceLabel(s)}
                      {s.current && (
                        <span className="ml-2 text-2xs font-medium uppercase tracking-wide text-success bg-success-bg rounded px-1.5 py-0.5 align-middle">
                          This device
                        </span>
                      )}
                    </p>
                    <p className="text-2xs text-muted truncate">
                      {s.lastSeenAt ? `Last active ${formatRelativeTime(s.lastSeenAt) ?? 'recently'}` : 'Not used yet'}
                      {s.lastSeenIp ? ` · ${s.lastSeenIp}` : ''}
                    </p>
                    <p className="text-2xs text-muted truncate">
                      Signed in {formatRelativeTime(s.sessionStartedAt) ?? 'recently'}
                      {expiresInLabel(s.sessionExpiresAt) ? ` · ${expiresInLabel(s.sessionExpiresAt)}` : ''}
                    </p>
                  </div>
                </div>
                <IconButton
                  size="sm"
                  variant="danger"
                  icon={<X size={14} />}
                  aria-label="Sign out this device"
                  title="Sign out this device"
                  loading={revokingId === s.id}
                  onClick={() => revokeSession(s.id)}
                />
              </div>
            ))}
          </div>
        )}
      </div>

      {changePasswordOpen && user && (
        <ChangePasswordModal
          onClose={() => setChangePasswordOpen(false)}
          onSuccess={() => onUserUpdate({ ...user, passwordChangedAt: new Date().toISOString() })}
          signInMethod={user.signInMethod}
        />
      )}
    </FinoraCard>
  );
}

function SecurityBasicsSkeletonFields() {
  return (
    <>
      <div className="border-b border-border py-3 space-y-2">
        <Skeleton.Text width="w-20" />
        <Skeleton.Text width="w-32" className="h-2.5" />
        <Skeleton.Block className="h-7 w-36 mt-2" />
      </div>
      <div className="flex items-center justify-between border-b border-border py-3">
        <div className="space-y-1.5">
          <Skeleton.Text width="w-32" />
          <Skeleton.Text width="w-28" className="h-2.5" />
        </div>
        <Skeleton.Block className="h-5 w-16" />
      </div>
    </>
  );
}

function ActiveSessionsSkeletonFields() {
  return (
    <div className="space-y-2">
      {[0, 1].map((i) => (
        <div key={i} className="flex items-center justify-between gap-3 border border-border rounded-lg px-3 py-2.5">
          <div className="flex items-center gap-2.5 flex-1 min-w-0">
            <Skeleton.Circle size={15} />
            <div className="min-w-0 flex-1 space-y-1.5">
              <Skeleton.Text width="w-40" />
              <Skeleton.Text width="w-28" className="h-2.5" />
            </div>
          </div>
          <Skeleton.Circle size={28} />
        </div>
      ))}
    </div>
  );
}
