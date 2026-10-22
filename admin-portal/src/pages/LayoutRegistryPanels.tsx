import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, Plus, X } from 'lucide-react';
import { adminLayoutRegistryApi, banksApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import { withSep } from '../lib/withSep';
import type { BankChoiceCount, BankDto, BankOriginWeek, LayoutProfileView, LayoutReviewReason, LayoutStatus, RegistryEntry } from '../types';

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
  HOLDER_NAME_UNREADABLE: "Holder's name could not be read",
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
    returnToAutomatic: useMutation({ mutationFn: adminLayoutRegistryApi.returnToAutomatic, onSuccess: refresh, onError: fail }),
    createProfile: useMutation({
      mutationFn: (v: { name: string; bankId: string }) => adminLayoutRegistryApi.createProfile(v.name, v.bankId),
      onSuccess: refresh,
      onError: fail,
    }),
    updateProfile: useMutation({
      mutationFn: (v: { profileId: string; changes: { name?: string; bankId?: string } }) =>
        adminLayoutRegistryApi.updateProfile(v.profileId, v.changes),
      onSuccess: refresh,
      onError: fail,
    }),
  };
}

/** Each reason with the upload that first raised it in this flag (V270), when that is known.
 *  A layout flagged before sources were recorded shows its reasons without one. */
function ReasonChips({ reasons, sources }: { reasons: LayoutReviewReason[]; sources: Record<string, string> }) {
  return (
    <div className="flex flex-wrap gap-1">
      {reasons.map((r) => (
        <span key={r} className="inline-flex flex-col text-[11px] px-1.5 py-0.5 rounded bg-warning-bg text-warning">
          <span>{describeReason(r)}</span>
          {sources[r] && (
            <span className="font-mono text-[10px] opacity-80" aria-label={`raised by upload ${sources[r]}`}>
              from {sources[r]}
            </span>
          )}
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

/** "Return to automatic" for a layout whose profile an operator decided (linked or removed). */
function ReturnToAutomatic({ entry, disabled, onReturn }: {
  entry: RegistryEntry; disabled: boolean; onReturn: () => void;
}) {
  if (entry.profileLinkSource !== 'MANUAL' || disabled) return null;
  return (
    <button type="button" onClick={onReturn}
      aria-label={`Return ${entry.fingerprint} to automatic grouping`}
      className="ml-2 text-[11px] text-primary">
      Return to automatic
    </button>
  );
}

function ProfilePicker({ entry, profiles, disabled, onLink, onReturn }: {
  entry: RegistryEntry; profiles: LayoutProfileView[]; disabled: boolean; onLink: (profileId: string) => void;
  onReturn: () => void;
}) {
  if (entry.profileId) {
    return (
      <span className="text-xs text-ink inline-block min-w-[14rem]">
        {entry.profileName} · v{entry.profileVersion}
        {entry.previousProfileVersion != null && (
          <span className="ml-1 text-muted">(was v{entry.previousProfileVersion})</span>
        )}
        {entry.profileLinkSource === 'AUTO' && <span className="ml-1 text-muted">(auto)</span>}
        <ReturnToAutomatic entry={entry} disabled={disabled} onReturn={onReturn} />
      </span>
    );
  }
  if (entry.profileLinkSource === 'MANUAL') {
    return (
      <span className="text-xs text-muted">
        Removed from grouping by an admin
        <ReturnToAutomatic entry={entry} disabled={disabled} onReturn={onReturn} />
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
      {profiles.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
    </select>
  );
}

/** Banks an admin can give a profile: every registered bank except OTHER, which means "no bank". */
function useProfileBanks(): BankDto[] {
  const banksQ = useQuery({ queryKey: ['banks-picker'], queryFn: () => banksApi.search() });
  return (banksQ.data ?? []).filter((b) => b.id !== 'OTHER');
}

/** "Users chose: HDFC Bank 2 · Other bank 1" for one layout, or nothing when no one chose. */
function UsersChose({ fingerprint, choices }: { fingerprint: string; choices: BankChoiceCount[] }) {
  const mine = choices.filter((c) => c.fingerprint === fingerprint);
  if (mine.length === 0) return null;
  return (
    <p className="mt-1 text-[11px] text-muted">
      {`Users chose: ${mine.map((c) => `${c.bankName} ${c.count}`).join(' · ')}`}
    </p>
  );
}

function weekLabel(iso: string) {
  const date = new Date(`${iso}T00:00:00`);
  return Number.isNaN(date.getTime()) ? iso
    : withSep(date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }));
}

/** Accounts created by imports each week, by where their bank came from (spec 2026-10-22, §8). */
function BankOriginsTable() {
  const originsQ = useQuery({ queryKey: ['layout-bank-origins'], queryFn: () => adminLayoutRegistryApi.bankOrigins() });
  const weeks: BankOriginWeek[] = originsQ.data ?? [];
  if (originsQ.isLoading || originsQ.isError || weeks.length === 0) return null;
  return (
    <div className="bg-card border border-border rounded-xl2 p-3 mb-4 overflow-x-auto">
      <h3 id="bank-origins-title" className="text-sm font-semibold text-ink mb-1">Where new accounts got their bank</h3>
      <p className="text-xs text-muted mb-2">
        Accounts created by statement imports. "From a profile" means the file named no bank and its
        layout's profile supplied one.
      </p>
      <table aria-labelledby="bank-origins-title" className="w-full text-sm">
        <thead>
          <tr className="text-left text-[10px] uppercase text-muted border-b border-border">
            <th className="py-1.5 pr-3">Week of</th>
            <th className="py-1.5 pr-3">From the file</th>
            <th className="py-1.5 pr-3">From a profile</th>
            <th className="py-1.5 pr-3">Chosen by the user</th>
            <th className="py-1.5">Other bank</th>
          </tr>
        </thead>
        <tbody>
          {weeks.map((w) => (
            <tr key={w.weekStart} className="border-t border-border">
              <td className="py-1.5 pr-3 text-xs text-ink whitespace-nowrap">{weekLabel(w.weekStart)}</td>
              <td className="py-1.5 pr-3 text-xs text-ink">{w.fromFile}</td>
              <td className="py-1.5 pr-3 text-xs text-ink">{w.fromProfile}</td>
              <td className="py-1.5 pr-3 text-xs text-ink">{w.chosenByUser}</td>
              <td className="py-1.5 text-xs text-ink">{w.otherBank}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function ReviewQueuePanel() {
  const { hasPermission } = useAdminAuth();
  const canManage = hasPermission('LAYOUT_REGISTRY_MANAGE');
  const [actionError, setActionError] = useState<string | null>(null);
  const queueQ = useQuery({ queryKey: ['layout-review-queue'], queryFn: adminLayoutRegistryApi.reviewQueue });
  const profilesQ = useQuery({ queryKey: ['layout-profiles'], queryFn: adminLayoutRegistryApi.profiles });
  const choicesQ = useQuery({ queryKey: ['layout-bank-choices'], queryFn: adminLayoutRegistryApi.bankChoices });
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
                <th className="p-3">Latest upload</th>
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
                  <td className="p-3 font-mono text-xs text-ink whitespace-nowrap">{entry.fingerprint}</td>
                  <td className="p-3">
                    <ReasonChips reasons={entry.reviewReasons} sources={entry.reviewReasonSources ?? {}} />
                    <UsersChose fingerprint={entry.fingerprint} choices={choicesQ.data ?? []} />
                  </td>
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
                      onLink={(profileId) => m.link.mutate({ fingerprint: entry.fingerprint, profileId })}
                      onReturn={() => m.returnToAutomatic.mutate(entry.fingerprint)} />
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
      size={Math.max(value.length + 2, 16)}
      className="bg-transparent text-sm font-semibold text-ink border border-transparent hover:border-border focus:border-border rounded px-1 py-0.5 max-w-full"
    />
  );
}

export function ProfilesPanel() {
  const { hasPermission } = useAdminAuth();
  const canManage = hasPermission('LAYOUT_REGISTRY_MANAGE');
  const [actionError, setActionError] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [newBank, setNewBank] = useState('');
  const banks = useProfileBanks();
  const profilesQ = useQuery({ queryKey: ['layout-profiles'], queryFn: adminLayoutRegistryApi.profiles });
  const registryQ = useQuery({ queryKey: ['layout-registry'], queryFn: adminLayoutRegistryApi.registry });
  const m = useRegistryMutations(setActionError);
  const unassigned = (registryQ.data ?? []).filter((e) => !e.profileId);
  // Layouts an admin took out of grouping: listed here so they can be handed back even when they
  // are not in the review queue.
  const removedByAdmin = unassigned.filter((e) => e.profileLinkSource === 'MANUAL');

  return (
    <div>
      <p className="text-xs text-muted mb-3">
        Layouts are grouped automatically by detected bank and account type: when a bank changes its
        statement format, the new layout joins the same profile as its next version. Versions follow
        when each layout first appeared — a layout added later that appeared earlier takes its place
        and the ones after it move up — and two can be in use at once. Moving or removing a layout
        here is final; automatic grouping will not undo it.
      </p>
      {actionError && <p role="alert" className="text-sm text-danger mb-3">{actionError}</p>}
      <BankOriginsTable />
      {canManage && (
        <form
          className="flex flex-wrap items-center gap-2 mb-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (!newName.trim() || !newBank) return;
            m.createProfile.mutate({ name: newName.trim(), bankId: newBank },
              { onSuccess: () => { setNewName(''); setNewBank(''); } });
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
          <select
            aria-label="New profile bank"
            value={newBank}
            onChange={(e) => setNewBank(e.target.value)}
            className="bg-card text-ink border border-border rounded px-1 py-1 text-sm"
          >
            <option value="">Choose a bank…</option>
            {banks.map((b) => <option key={b.id} value={b.id}>{b.officialName}</option>)}
          </select>
          <button type="submit" disabled={!newName.trim() || !newBank || m.createProfile.isPending}
            className="flex items-center gap-1 text-sm text-primary disabled:opacity-60">
            <Plus size={14} /> Create profile
          </button>
        </form>
      )}
      {removedByAdmin.length > 0 && (
        <div className="bg-card border border-border rounded-xl2 p-3 mb-3">
          <h3 className="text-sm font-semibold text-ink mb-1">Removed from grouping by an admin</h3>
          <p className="text-xs text-muted mb-2">
            Automatic grouping leaves these alone until they are returned to it.
          </p>
          <table className="w-full text-sm">
            <tbody>
              {removedByAdmin.map((e) => (
                <tr key={e.fingerprint} className="border-t border-border">
                  <td className="py-1.5 pr-3 font-mono text-xs text-ink whitespace-nowrap">{e.fingerprint}</td>
                  <td className="py-1.5 pr-3 text-xs text-ink">{e.name ?? <span className="text-muted">Unnamed</span>}</td>
                  <td className="py-1.5 text-right">
                    <ReturnToAutomatic entry={e} disabled={!canManage}
                      onReturn={() => m.returnToAutomatic.mutate(e.fingerprint)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
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
                <div className="flex flex-wrap items-center gap-2">
                  {canManage ? (
                    <ProfileNameField profile={profile}
                      onSave={(name) => m.updateProfile.mutate({ profileId: profile.id, changes: { name } })} />
                  ) : (
                    <h3 className="text-sm font-semibold text-ink">{profile.name}</h3>
                  )}
                  {profile.bankId
                    ? <span className="text-xs text-muted">{`Bank: ${profile.bankName ?? profile.bankId}`}</span>
                    : <span className="text-xs text-danger">No bank set</span>}
                  {canManage && (
                    <select
                      aria-label={`Bank for ${profile.name}`}
                      value={profile.bankId ?? ''}
                      onChange={(e) => {
                        if (e.target.value) m.updateProfile.mutate({ profileId: profile.id, changes: { bankId: e.target.value } });
                      }}
                      className="bg-card text-ink border border-border rounded px-1 py-1 text-xs"
                    >
                      {!profile.bankId && <option value="">Choose a bank…</option>}
                      {/* A bank the list no longer has (an admin-added bank since removed) is still
                          this profile's bank; shown as such rather than silently as the first bank. */}
                      {profile.bankId && !banks.some((b) => b.id === profile.bankId) && (
                        <option value={profile.bankId}>{profile.bankName ?? profile.bankId}</option>
                      )}
                      {banks.map((b) => <option key={b.id} value={b.id}>{b.officialName}</option>)}
                    </select>
                  )}
                </div>
                {canManage && unassigned.length > 0 && (
                  <select
                    aria-label={`Add a layout to ${profile.name}`}
                    value=""
                    onChange={(e) => {
                      if (e.target.value) m.link.mutate({ fingerprint: e.target.value, profileId: profile.id });
                    }}
                    className="bg-card text-ink border border-border rounded px-1 py-1 text-xs"
                  >
                    <option value="">Add a layout…</option>
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
                        <td className="py-1.5 pr-3 text-xs font-semibold text-ink w-24">
                          v{v.profileVersion}
                          {v.previousProfileVersion != null && (
                            <span className="ml-1 font-normal text-muted" title={`Moved ${formatWhen(v.profileVersionChangedAt)}`}>
                              (was v{v.previousProfileVersion})
                            </span>
                          )}
                        </td>
                        <td className="py-1.5 pr-3 text-[11px] text-muted w-40">
                          {v.profileLinkSource === 'AUTO' ? 'auto' : 'manual'}
                          <ReturnToAutomatic entry={v} disabled={!canManage}
                            onReturn={() => m.returnToAutomatic.mutate(v.fingerprint)} />
                        </td>
                        <td className="py-1.5 pr-3 font-mono text-xs text-ink whitespace-nowrap">{v.fingerprint}</td>
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
