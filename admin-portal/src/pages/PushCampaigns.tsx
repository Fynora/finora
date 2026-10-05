import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { adminPushCampaignApi } from '../api/endpoints';
import type { PushCampaign } from '../types';
import { formatIst } from '../lib/istTime';
import { CampaignEditor } from './push-campaigns/CampaignEditor';
import { CampaignDetail } from './push-campaigns/CampaignDetail';
import {
  apiMessage, AUDIENCE_LABELS, CAMPAIGN_STATUS_LABELS, campaignStatusTone, scheduleSummary,
} from './push-campaigns/labels';

/** The API returns at most this many campaigns, newest first. */
const LIST_LIMIT = 200;

/**
 * Admin push campaigns: write any notification, pick who gets it, test it on a real phone, then
 * send it now or schedule it (once, or every day at a time in IST) and stop it whenever.
 *
 * The page only drives the API. Every rule -- the one-push-a-day limit, the 07:00-21:59 IST window,
 * the rollout limit, which status allows which action -- lives on the server, and a refusal is shown
 * with the server's own words. Buttons are hidden only where the action cannot apply at all.
 *
 * Three views in one route: the list, the editor (create or edit) and one campaign's detail with
 * its run history. `view` is local state, not a URL, because the list is short (the API returns the
 * newest 200) and no view needs to be linkable.
 */
type View =
  | { kind: 'list' }
  | { kind: 'new' }
  | { kind: 'edit'; campaign: PushCampaign }
  | { kind: 'detail'; id: string };

function PushCampaignsContent() {
  const queryClient = useQueryClient();
  const [view, setView] = useState<View>({ kind: 'list' });

  const list = useQuery({
    queryKey: ['push-campaigns'],
    queryFn: () => adminPushCampaignApi.list(),
  });

  function refreshAll(id?: string) {
    void queryClient.invalidateQueries({ queryKey: ['push-campaigns'] });
    if (id) void queryClient.invalidateQueries({ queryKey: ['push-campaign', id] });
  }

  if (view.kind === 'new') {
    return (
      <CampaignEditor
        onCancel={() => setView({ kind: 'list' })}
        onSaved={(campaign) => {
          refreshAll();
          setView({ kind: 'detail', id: campaign.id });
        }}
      />
    );
  }

  if (view.kind === 'edit') {
    return (
      <CampaignEditor
        initial={view.campaign}
        onCancel={() => setView({ kind: 'detail', id: view.campaign.id })}
        onSaved={(campaign) => {
          refreshAll(campaign.id);
          setView({ kind: 'detail', id: campaign.id });
        }}
      />
    );
  }

  if (view.kind === 'detail') {
    return (
      // Keyed by id: cloning swaps this view to the copy, and without a key React would reuse the
      // same instance, carrying the old campaign's notice, test recipient and open dialog across.
      <CampaignDetail
        key={view.id}
        id={view.id}
        onBack={() => setView({ kind: 'list' })}
        onEdit={(campaign) => setView({ kind: 'edit', campaign })}
        onCloned={(copy) => setView({ kind: 'detail', id: copy.id })}
      />
    );
  }

  const columns: DataTableColumn<PushCampaign>[] = [
    {
      header: 'Campaign',
      render: (c) => (
        <button type="button" onClick={() => setView({ kind: 'detail', id: c.id })} className="text-left">
          <p className="font-medium text-ink hover:text-primary">{c.name}</p>
          <p className="text-xs text-muted">{c.title}</p>
        </button>
      ),
    },
    {
      header: 'Status',
      render: (c) => (
        <span className={`inline-block rounded-full border px-2 py-0.5 text-xs ${campaignStatusTone(c.status)}`}>
          {CAMPAIGN_STATUS_LABELS[c.status]}
        </span>
      ),
    },
    { header: 'Who', render: (c) => AUDIENCE_LABELS[c.audienceType], cellClassName: 'text-muted' },
    { header: 'When', render: (c) => scheduleSummary(c), cellClassName: 'text-muted' },
    {
      header: 'Next send',
      render: (c) => (c.status === 'ACTIVE' ? formatIst(c.nextRunAt) : '—'),
      cellClassName: 'text-muted',
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm text-muted max-w-2xl">
          Write a push notification, choose who gets it, test it on a real phone, then send it now or
          schedule it. Nobody gets more than one campaign push a day, whichever campaigns are running.
        </p>
        <button
          type="button"
          onClick={() => setView({ kind: 'new' })}
          className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2.5 flex-shrink-0"
        >
          <Plus size={15} /> New campaign
        </button>
      </div>

      {list.isError && (
        <p className="text-sm text-danger" role="alert">{apiMessage(list.error, 'Could not load campaigns.')}</p>
      )}

      <DataTable
        columns={columns}
        rows={list.data ?? []}
        keyFor={(c) => c.id}
        loading={list.isLoading}
        // A failed load must not read as "nothing exists": that would invite creating a duplicate.
        emptyMessage={list.isError ? 'Campaigns could not be loaded.' : 'No campaigns yet. Create one to get started.'}
      />
      {(list.data?.length ?? 0) >= LIST_LIMIT && (
        <p className="text-xs text-muted">Showing the newest {LIST_LIMIT} campaigns.</p>
      )}
    </div>
  );
}

export default function PushCampaigns() {
  return (
    <AdminLayout
      title="Push Campaigns"
      subtitle="Announcements and reminders sent to people's phones, on your schedule."
    >
      <RequirePermission permission="PUSH_CAMPAIGN_MANAGE">
        <PushCampaignsContent />
      </RequirePermission>
    </AdminLayout>
  );
}
