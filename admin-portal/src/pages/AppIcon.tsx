import { useId, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { FormPanel } from '../components/FormPanel';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { adminAppIconApi } from '../api/endpoints';
import type { AppIconNow, AppIconOption, AppIconScheduleEntry, AppIconScheduleSaveRequest, AppIconScheduleState } from '../types';
import { formatIst, isoToIstInput, istInputToIso } from '../lib/istTime';

const LABEL_MAX = 80;
const INPUT = 'w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm';
const LABEL = 'text-xs font-medium text-muted mb-1 block';

const STATE_LABELS: Record<AppIconScheduleState, string> = {
  ACTIVE: 'Showing now',
  UPCOMING: 'Upcoming',
  ENDED: 'Ended',
};

function stateTone(state: AppIconScheduleState): string {
  switch (state) {
    case 'ACTIVE':
      return 'bg-success-bg text-success border-success';
    case 'UPCOMING':
      return 'bg-info-bg text-info border-info';
    default:
      return 'bg-bg text-muted border-border';
  }
}

function apiMessage(err: unknown, fallback: string): string {
  return (err as { response?: { data?: { message?: string } } })?.response?.data?.message ?? fallback;
}

function iconName(key: string, icons: AppIconOption[] | undefined): string {
  if (key === 'default') return 'Standard Fynora icon';
  return icons?.find((i) => i.key === key)?.label ?? key;
}

/** "Showing now: Diwali, until 10 Nov 2026, 00:00 IST" -- what phones are told right now. */
function ShowingNow({ now, icons, loading, failed }: {
  now: AppIconNow | undefined; icons: AppIconOption[] | undefined; loading: boolean; failed: boolean;
}) {
  let text: string;
  if (failed) text = 'Could not load which icon is showing.';
  else if (loading || !now) text = 'Loading…';
  else if (now.until) text = `${iconName(now.icon, icons)}, until ${formatIst(now.until)}`;
  else text = `${iconName(now.icon, icons)} (nothing scheduled right now)`;
  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-4" role="status" aria-live="polite">
      <p className="text-sm text-ink">
        <span className="font-semibold">Showing now:</span> <span data-testid="showing-now">{text}</span>
      </p>
      <p className="text-xs text-muted mt-1">
        Phones only follow this schedule once they have the app version that includes festival icons. A phone
        picks up a change the next time the app is opened or brought back, usually within a minute.
      </p>
    </div>
  );
}

/**
 * Add or edit one window. Times are typed and shown in IST whatever the laptop's time zone is. The
 * server is the authority on every rule (known icon, end after start, end not passed, no overlap);
 * the two checks here only save a round trip, and the server's own message is shown otherwise.
 */
function ScheduleEditor({ initial, icons, onCancel, onSaved }: {
  initial?: AppIconScheduleEntry;
  icons: AppIconOption[];
  onCancel: () => void;
  onSaved: () => void;
}) {
  const id = useId();
  const [icon, setIcon] = useState(initial?.icon ?? icons[0]?.key ?? '');
  const [label, setLabel] = useState(initial?.label ?? '');
  const [startsAt, setStartsAt] = useState(initial ? isoToIstInput(initial.startsAt) : '');
  const [endsAt, setEndsAt] = useState(initial ? isoToIstInput(initial.endsAt) : '');
  const [error, setError] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: (body: AppIconScheduleSaveRequest) =>
      initial ? adminAppIconApi.update(initial.id, body) : adminAppIconApi.create(body),
    onSuccess: () => onSaved(),
    onError: (err) => setError(apiMessage(err, 'Could not save the entry.')),
  });

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!icon || !label.trim() || !startsAt || !endsAt) {
      setError('Icon, name, start and end are all required.');
      return;
    }
    const start = istInputToIso(startsAt);
    const end = istInputToIso(endsAt);
    if (new Date(end).getTime() <= new Date(start).getTime()) {
      setError('The end must be after the start.');
      return;
    }
    setError(null);
    save.mutate({
      icon,
      label: label.trim(),
      startsAt: start,
      endsAt: end,
      ...(initial ? { expectedVersion: initial.version } : {}),
    });
  }

  return (
    <FormPanel
      title={initial ? `Edit “${initial.label}”` : 'Schedule a festival icon'}
      onCancel={onCancel}
      onSubmit={onSubmit}
      error={error}
      submitting={save.isPending}
      submitLabel={initial ? 'Save changes' : 'Add to schedule'}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label htmlFor={`${id}-icon`} className={LABEL}>Icon</label>
          <select id={`${id}-icon`} className={INPUT} value={icon} onChange={(e) => setIcon(e.target.value)}>
            {icons.map((o) => (
              <option key={o.key} value={o.key}>{o.label}</option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor={`${id}-label`} className={LABEL}>Name (for example “Diwali 2026”)</label>
          <input
            id={`${id}-label`}
            className={INPUT}
            value={label}
            maxLength={LABEL_MAX}
            onChange={(e) => setLabel(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor={`${id}-start`} className={LABEL}>Starts (IST)</label>
          <input
            id={`${id}-start`}
            type="datetime-local"
            className={INPUT}
            value={startsAt}
            onChange={(e) => setStartsAt(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor={`${id}-end`} className={LABEL}>Ends (IST) — the standard icon comes back at this time</label>
          <input
            id={`${id}-end`}
            type="datetime-local"
            className={INPUT}
            value={endsAt}
            onChange={(e) => setEndsAt(e.target.value)}
          />
        </div>
      </div>
      <p className="text-xs text-muted">
        Only one icon can be scheduled at a time, so entries cannot overlap. One entry may start at the exact
        minute another ends.
      </p>
    </FormPanel>
  );
}

type View = { kind: 'list' } | { kind: 'new' } | { kind: 'edit'; entry: AppIconScheduleEntry };

function AppIconContent() {
  const queryClient = useQueryClient();
  const [view, setView] = useState<View>({ kind: 'list' });
  const [deleting, setDeleting] = useState<AppIconScheduleEntry | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const current = useQuery({ queryKey: ['app-icon-current'], queryFn: () => adminAppIconApi.current() });
  const icons = useQuery({ queryKey: ['app-icon-icons'], queryFn: () => adminAppIconApi.icons() });
  const schedule = useQuery({ queryKey: ['app-icon-schedule'], queryFn: () => adminAppIconApi.list() });

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['app-icon-current'] });
    void queryClient.invalidateQueries({ queryKey: ['app-icon-schedule'] });
  }

  const remove = useMutation({
    mutationFn: (entry: AppIconScheduleEntry) => adminAppIconApi.remove(entry.id),
    onSuccess: (_, entry) => {
      setDeleting(null);
      setNotice(`Deleted “${entry.label}”.`);
      refresh();
    },
    onError: (err) => {
      setDeleting(null);
      setNotice(apiMessage(err, 'Could not delete the entry.'));
    },
  });

  const showingNow = (
    <ShowingNow now={current.data} icons={icons.data} loading={current.isLoading} failed={current.isError && !current.data} />
  );

  if (view.kind !== 'list') {
    return (
      <div className="space-y-6">
        {showingNow}
        {icons.data ? (
          <ScheduleEditor
            initial={view.kind === 'edit' ? view.entry : undefined}
            icons={icons.data}
            onCancel={() => setView({ kind: 'list' })}
            onSaved={() => {
              setNotice(view.kind === 'edit' ? 'Changes saved.' : 'Added to the schedule.');
              refresh();
              setView({ kind: 'list' });
            }}
          />
        ) : (
          <p className="text-sm text-danger" role="alert">
            {icons.isError ? 'Could not load the list of icons.' : 'Loading icons…'}
          </p>
        )}
      </div>
    );
  }

  const columns: DataTableColumn<AppIconScheduleEntry>[] = [
    {
      header: 'Name',
      render: (e) => (
        <div>
          <p className="font-medium text-ink">{e.label}</p>
          <p className="text-xs text-muted">{e.iconLabel ?? `${e.icon} (no longer in the app)`}</p>
        </div>
      ),
    },
    { header: 'Starts', render: (e) => formatIst(e.startsAt), cellClassName: 'text-muted' },
    { header: 'Ends', render: (e) => formatIst(e.endsAt), cellClassName: 'text-muted' },
    {
      header: 'Status',
      render: (e) => (
        <span className={`inline-block rounded-full border px-2 py-0.5 text-xs ${stateTone(e.state)}`}>
          {STATE_LABELS[e.state]}
        </span>
      ),
    },
    {
      header: 'Last changed',
      render: (e) => (
        <div className="text-xs text-muted">
          <p>{e.updatedByEmail ?? 'a removed admin account'}</p>
          <p>{formatIst(e.updatedAt)}</p>
        </div>
      ),
    },
    {
      header: '',
      render: (e) => (
        <div className="flex justify-end gap-3 text-sm">
          {/* An ended entry cannot be saved again (its end has passed); it can still be deleted. */}
          {e.state !== 'ENDED' && (
            <button type="button" className="text-primary hover:underline" onClick={() => setView({ kind: 'edit', entry: e })}>
              Edit
            </button>
          )}
          <button type="button" className="text-danger hover:underline" onClick={() => setDeleting(e)}>
            Delete
          </button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      {showingNow}

      <div className="flex items-center justify-between gap-4">
        <p className="text-sm text-muted max-w-2xl">
          Plan which home-screen icon the app shows, and when. Outside every entry the standard icon shows, so
          it comes back on its own when an entry ends.
        </p>
        <button
          type="button"
          onClick={() => { setNotice(null); setView({ kind: 'new' }); }}
          disabled={!icons.data}
          className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2.5 flex-shrink-0 disabled:opacity-50"
        >
          <Plus size={15} /> Add entry
        </button>
      </div>

      {notice && <p className="text-sm text-ink" role="status">{notice}</p>}
      {schedule.isError && (
        <p className="text-sm text-danger" role="alert">{apiMessage(schedule.error, 'Could not load the schedule.')}</p>
      )}

      <DataTable
        columns={columns}
        rows={schedule.data ?? []}
        keyFor={(e) => e.id}
        loading={schedule.isLoading}
        emptyMessage={schedule.isError ? 'The schedule could not be loaded.' : 'Nothing scheduled. The standard icon shows.'}
      />

      {deleting && (
        <ConfirmDialog
          title="Delete this entry?"
          message={
            deleting.state === 'ACTIVE'
              ? `“${deleting.label}” is showing now. Deleting it switches phones back to the standard icon.`
              : `“${deleting.label}” (${formatIst(deleting.startsAt)} to ${formatIst(deleting.endsAt)}) will be removed.`
          }
          confirmLabel="Delete"
          danger
          busy={remove.isPending}
          onConfirm={() => remove.mutate(deleting)}
          onCancel={() => setDeleting(null)}
        />
      )}
    </div>
  );
}

export default function AppIcon() {
  return (
    <AdminLayout title="Festival app icon" subtitle="Change the app's home-screen icon for festivals, on a schedule.">
      <RequirePermission permission="SYSTEM_SETTINGS">
        <AppIconContent />
      </RequirePermission>
    </AdminLayout>
  );
}
