import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, Plus, X } from 'lucide-react';
import { adminLayoutRegistryApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import type { LayoutProfileView, LayoutReviewReason, LayoutStatus, RegistryEntry } from '../types';

/**
 * The layout registry's curation panels (V243), shown as tabs on Layout Intelligence.
 *
 * Needs review — layouts the engine flagged while staging a statement: never seen before, a
 * verification check that did not pass, mostly blank descriptions, or a staging failure. Resolving
 * one marks its reasons as reviewed, so the same reasons do not flag it again; a new reason does.
 *
 * Profiles — families of layouts ("Kotak Mahindra Bank — Credit Card"), each member at a version.
 * The engine groups layouts on its own by detected bank and account type (V244): a bank's new
 * statement format joins its profile as the next version, with no operator work. An operator can
 * still move a layout, or take it out — either decision is final and the engine leaves it alone.
 */

const REASON_LABELS: Record<string, string> = {
  NEW_LAYOUT: 'New layout',
  VERIFICATION_NOT_PASSED: 'Verification did not pass',
  BLANK_DESCRIPTIONS: 'Mostly blank descriptions',
  STAGING_FAILED: 'Staging failed',
  IDENTITY_CONFLICT: 'Seen as a different bank or account type',
};

/** Plain words for a reason code; a per-rule verification reason names its rule. */
export function describeReason(reason: LayoutReviewReason | string): string {
  const [base, rule] = reason.split(':', 2);
  const label = REASON_LABELS[base] ?? base;
  return rule ? `${label}: ${rule}` : label;
}

const STATUSES: LayoutStatus[] = ['OBSERVED', 'UNDER_REVIEW', 'SUPPORTED', 'UNSUPPORTED'];

function formatWhen(iso: string | null) {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

function errorMessage(error: unknown) {
  const response = (error as { response?: { data?: { message?: string } } })?.response;
  return response?.data?.message ?? 'That action could not be completed.';
}

function useRegistryMutations(onError: (message: string | null) => void) {
  const queryClient = useQueryClient();
  const refresh = () => {
    onError(null);
    void queryClient.invalidateQueries({ queryKey: ['layout-review-queue'] });
    void queryClient.invalidateQueries({ queryKey: ['layout-registry'] });
    void queryClient.invalidateQueries({ queryKey: ['layout-profiles'] });
  };
  const fail = (error: unknown) => onError(errorMessage(error));
  return {
    resolve: useMutation({ mutationFn: adminLayoutRegistryApi.resolveReview, onSuccess: refresh, onError: fail }),
    update: useMutation({
      mutationFn: (v: { fingerprint: string; changes: { name?: string | null; status?: LayoutStatus } }) =>
        adminLayoutRegistryApi.update(v.fingerprint, v.changes),
      onSuccess: refresh,
      onError: fail,
    }),
    link: useMutation({
      mutationFn: (v: { fingerprint: string; profileId: string }) =>
        adminLayoutRegistryApi.linkToProfile(v.fingerprint, v.profileId),
      onSuccess: refresh,
      onError: fail,
    }),
    unlink: useMutation({ mutationFn: adminLayoutRegistryApi.unlinkFromProfile, onSuccess: refresh, onError: fail }),
    createProfile: useMutation({ mutationFn: adminLayoutRegistryApi.createProfile, onSuccess: refresh, onError: fail }),
    renameProfile: useMutation({
      mutationFn: (v: { profileId: string; name: string }) => adminLayoutRegistryApi.renameProfile(v.profileId, v.name),
      onSuccess: refresh,
      onError: fail,
    }),
  };
}

function ReasonChips({ reasons }: { reasons: LayoutReviewReason[] }) {
  return (
    <div className="flex flex-wrap gap-1">
      {reasons.map((r) => (
        <span key={r} className="text-[11px] px-1.5 py-0.5 rounded bg-warning/10 text-warning">
          {describeReason(r)}
        </span>
      ))}
    </div>
  );
}

/** A name field that saves on blur or Enter, and only when the value changed. */
function NameField({ entry, disabled, onSave }: {
  entry: RegistryEntry; disabled: boolean; onSave: (name: string | null) => void;
}) {
  const [value, setValue] = useState(entry.name ?? '');
  const save = () => {
    const next = value.trim() === '' ? null : value.trim();
    if (next !== (entry.name ?? null)) onSave(next);
  };
  return (
    <input
      aria-label={`Name for ${entry.fingerprint}`}
      value={value}
      disabled={disabled}
      placeholder="Unnamed"
      maxLength={120}
      onChange={(e) => setValue(e.target.value)}
      onBlur={save}
      onKeyDown={(e) => { if (e.key === 'Enter') save(); }}
      className="bg-card text-ink border border-border rounded px-2 py-1 text-xs w-40 disabled:opacity-60"
    />
  );
}

function ProfilePicker({ entry, profiles, disabled, onLink }: {
  entry: RegistryEntry; profiles: LayoutProfileView[]; disabled: boolean; onLink: (profileId: string) => void;
}) {
  if (entry.profileId) {
    return (
      <span className="text-xs text-ink">
        {entry.profileName} · v{entry.profileVersion}
        {entry.profileLinkSource === 'AUTO' && <span className="ml-1 text-muted">(auto)</span>}
      </span>
    );
  }
  return (
    <select
      aria-label={`Add ${entry.fingerprint} to a profile`}
      value=""
      disabled={disabled || profiles.length === 0}
      onChange={(e) => { if (e.target.value) onLink(e.target.value); }}
      className="bg-card text-ink border border-border rounded px-1 py-1 text-xs disabled:opacity-60"
    >
      <option value="">{profiles.length === 0 ? 'No profiles yet' : 'Add to profile…'}</option>
      {profiles.map((p) => <option key={p.id} value={p.id}>{p.name} (next: v{p.versions.length === 0 ? 1 : Math.max(...p.versions.map((v) => v.profileVersion ?? 0)) + 1})</option>)}
    </select>
  );
}

export function ReviewQueuePanel() {
  const { hasPermission } = useAdminAuth();
  const canManage = hasPermission('LAYOUT_REGISTRY_MANAGE');
  const [actionError, setActionError] = useState<string | null>(null);
  const queueQ = useQuery({ queryKey: ['layout-review-queue'], queryFn: adminLayoutRegistryApi.reviewQueue });
  const profilesQ = useQuery({ queryKey: ['layout-profiles'], queryFn: adminLayoutRegistryApi.profiles });
  const m = useRegistryMutations(setActionError);
  const queue = queueQ.data ?? [];

  return (
    <div>
      <p className="text-xs text-muted mb-3">
        Layouts flagged while a statement was being staged. Resolving one marks its reasons as
        reviewed; the same reasons will not flag it again, a new one will.
        {!canManage && ' You can view this queue; curating it needs LAYOUT_REGISTRY_MANAGE.'}
      </p>
      {actionError && <p role="alert" className="text-sm text-danger mb-3">{actionError}</p>}
      <div className="bg-card border border-border rounded-xl2 overflow-x-auto">
        {queueQ.isLoading ? (
          <p className="p-4 text-sm text-muted">Loading…</p>
        ) : queueQ.isError ? (
          <p className="p-4 text-sm text-danger">Couldn't load the review queue.</p>
        ) : queue.length === 0 ? (
          <p className="p-4 text-sm text-muted italic">Nothing waiting for review.</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
                <th className="p-3">Fingerprint</th>
                <th className="p-3">Why</th>
                <th className="p-3">Flagged</th>
                <th className="p-3">Analysis</th>
                <th className="p-3">Staged / confirmed</th>
                <th className="p-3">Name</th>
                <th className="p-3">Status</th>
                <th className="p-3">Profile</th>
                <th className="p-3" />
              </tr>
            </thead>
            <tbody>
              {queue.map((entry) => (
                <tr key={entry.fingerprint} className="border-b border-border last:border-0 align-top">
                  <td className="p-3 font-mono text-xs text-ink">{entry.fingerprint}</td>
                  <td className="p-3"><ReasonChips reasons={entry.reviewReasons} /></td>
                  <td className="p-3 text-xs text-muted">{formatWhen(entry.reviewFlaggedAt)}</td>
                  <td className="p-3 font-mono text-xs text-muted">{entry.reviewAnalysisReference ?? '—'}</td>
                  <td className="p-3 text-xs text-ink">{entry.stagingCount} / {entry.observationCount}</td>
                  <td className="p-3">
                    <NameField entry={entry} disabled={!canManage}
                      onSave={(name) => m.update.mutate({ fingerprint: entry.fingerprint, changes: { name } })} />
                  </td>
                  <td className="p-3">
                    <select
                      aria-label={`Status for ${entry.fingerprint}`}
                      value={entry.status}
                      disabled={!canManage}
                      onChange={(e) => m.update.mutate({
                        fingerprint: entry.fingerprint, changes: { status: e.target.value as LayoutStatus },
                      })}
                      className="bg-card text-ink border border-border rounded px-1 py-1 text-xs disabled:opacity-60"
                    >
                      {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
                    </select>
                  </td>
                  <td className="p-3">
                    <ProfilePicker entry={entry} profiles={profilesQ.data ?? []} disabled={!canManage}
                      onLink={(profileId) => m.link.mutate({ fingerprint: entry.fingerprint, profileId })} />
                  </td>
                  <td className="p-3">
                    {canManage && (
                      <button
                        type="button"
                        onClick={() => m.resolve.mutate(entry.fingerprint)}
                        disabled={m.resolve.isPending}
                        className="flex items-center gap-1 text-xs text-primary disabled:opacity-60"
                      >
                        <CheckCircle2 size={14} /> Resolve
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

/** A profile's name, editable in place; saves on blur or Enter when changed and not blank. */
function ProfileNameField({ profile, onSave }: { profile: LayoutProfileView; onSave: (name: string) => void }) {
  const [value, setValue] = useState(profile.name);
  const save = () => {
    const next = value.trim();
    if (next && next !== profile.name) onSave(next);
    else setValue(profile.name);
  };
  return (
    <input
      aria-label={`Profile name for ${profile.name}`}
      maxLength={120}
      value={value}
      onChange={(e) => setValue(e.target.value)}
      onBlur={save}
      onKeyDown={(e) => { if (e.key === 'Enter') save(); }}
      className="bg-transparent text-sm font-semibold text-ink border border-transparent hover:border-border focus:border-border rounded px-1 py-0.5"
    />
  );
}

export function ProfilesPanel() {
  const { hasPermission } = useAdminAuth();
  const canManage = hasPermission('LAYOUT_REGISTRY_MANAGE');
  const [actionError, setActionError] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const profilesQ = useQuery({ queryKey: ['layout-profiles'], queryFn: adminLayoutRegistryApi.profiles });
  const registryQ = useQuery({ queryKey: ['layout-registry'], queryFn: adminLayoutRegistryApi.registry });
  const m = useRegistryMutations(setActionError);
  const unassigned = (registryQ.data ?? []).filter((e) => !e.profileId);

  return (
    <div>
      <p className="text-xs text-muted mb-3">
        Layouts are grouped automatically by detected bank and account type: when a bank changes its
        statement format, the new layout joins the same profile as its next version. A version
        orders a bank's layouts by when they first appeared — two can be in use at once. Moving or
        removing a layout here is final; automatic grouping will not undo it.
      </p>
      {actionError && <p role="alert" className="text-sm text-danger mb-3">{actionError}</p>}
      {canManage && (
        <form
          className="flex items-center gap-2 mb-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (!newName.trim()) return;
            m.createProfile.mutate(newName.trim(), { onSuccess: () => setNewName('') });
          }}
        >
          <input
            aria-label="New profile name"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="e.g. Kotak Credit Card"
            maxLength={120}
            className="bg-card text-ink border border-border rounded px-2 py-1 text-sm w-64"
          />
          <button type="submit" disabled={!newName.trim() || m.createProfile.isPending}
            className="flex items-center gap-1 text-sm text-primary disabled:opacity-60">
            <Plus size={14} /> Create profile
          </button>
        </form>
      )}
      {profilesQ.isLoading ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : profilesQ.isError ? (
        <p className="text-sm text-danger">Couldn't load layout profiles.</p>
      ) : (profilesQ.data ?? []).length === 0 ? (
        <p className="text-sm text-muted italic">No layout profiles yet.</p>
      ) : (
        <div className="space-y-3">
          {(profilesQ.data ?? []).map((profile) => (
            <div key={profile.id} className="bg-card border border-border rounded-xl2 p-3">
              <div className="flex items-center justify-between mb-2">
                {canManage ? (
                  <ProfileNameField profile={profile}
                    onSave={(name) => m.renameProfile.mutate({ profileId: profile.id, name })} />
                ) : (
                  <h3 className="text-sm font-semibold text-ink">{profile.name}</h3>
                )}
                {canManage && unassigned.length > 0 && (
                  <select
                    aria-label={`Add a layout to ${profile.name}`}
                    value=""
                    onChange={(e) => {
                      if (e.target.value) m.link.mutate({ fingerprint: e.target.value, profileId: profile.id });
                    }}
                    className="bg-card text-ink border border-border rounded px-1 py-1 text-xs"
                  >
                    <option value="">Add layout as next version…</option>
                    {unassigned.map((e) => (
                      <option key={e.fingerprint} value={e.fingerprint}>
                        {e.fingerprint}{e.name ? ` — ${e.name}` : ''}
                      </option>
                    ))}
                  </select>
                )}
              </div>
              {profile.versions.length === 0 ? (
                <p className="text-xs text-muted italic">No layouts in this profile yet.</p>
              ) : (
                <table className="w-full text-sm">
                  <tbody>
                    {profile.versions.map((v) => (
                      <tr key={v.fingerprint} className="border-t border-border">
                        <td className="py-1.5 pr-3 text-xs font-semibold text-ink w-12">v{v.profileVersion}</td>
                        <td className="py-1.5 pr-3 text-[11px] text-muted w-14">
                          {v.profileLinkSource === 'AUTO' ? 'auto' : 'manual'}
                        </td>
                        <td className="py-1.5 pr-3 font-mono text-xs text-ink">{v.fingerprint}</td>
                        <td className="py-1.5 pr-3 text-xs text-ink">{v.name ?? <span className="text-muted">Unnamed</span>}</td>
                        <td className="py-1.5 pr-3 text-xs text-muted">{v.status}</td>
                        <td className="py-1.5 pr-3 text-xs text-muted">Last seen {formatWhen(v.lastSeen)}</td>
                        <td className="py-1.5 text-right">
                          {canManage && (
                            <button type="button" aria-label={`Remove ${v.fingerprint} from ${profile.name}`}
                              onClick={() => m.unlink.mutate(v.fingerprint)}
                              className="text-muted hover:text-danger">
                              <X size={14} />
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
