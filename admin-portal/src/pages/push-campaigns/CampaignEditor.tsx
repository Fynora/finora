import { useId, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { FormPanel } from '../../components/FormPanel';
import { adminPushCampaignApi } from '../../api/endpoints';
import type { PushAudienceType, PushCampaign, PushCampaignSaveRequest, PushScheduleKind } from '../../types';
import { isInputInsideWindow, isInsideWindow, isoToIstInput, istInputToIso, toTimeInput, todayIst, WINDOW_END, WINDOW_START } from '../../lib/istTime';
import { apiMessage, AUDIENCE_HELP, AUDIENCE_LABELS } from './labels';

const TITLE_MAX = 80;
const MESSAGE_MAX = 240;
const NAME_MAX = 120;

const INPUT = 'w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm';
const LABEL = 'text-xs font-medium text-muted mb-1 block';

/** Matches the server's rule: newlines are fine in a message, every other control character is not. */
function hasDisallowedControl(value: string): boolean {
  // eslint-disable-next-line no-control-regex
  return /[\u0000-\u0009\u000B-\u001F\u007F-\u009F]/.test(value);
}

/**
 * Create or edit a campaign (editing is only offered for a draft or paused one -- the server
 * enforces that too). `initial.version` goes back as `expectedVersion`, so if another admin saved
 * in the meantime the server answers 409 and the message is shown here instead of overwriting them.
 *
 * The 07:00-21:59 IST window is checked here only to save a round trip; the server is the authority
 * and its message is what is shown if the two ever disagree.
 */
export function CampaignEditor({
  initial, onCancel, onSaved,
}: {
  initial?: PushCampaign;
  onCancel: () => void;
  onSaved: (campaign: PushCampaign) => void;
}) {
  const id = useId();
  const [name, setName] = useState(initial?.name ?? '');
  const [title, setTitle] = useState(initial?.title ?? '');
  const [message, setMessage] = useState(initial?.message ?? '');
  const [audienceType, setAudienceType] = useState<PushAudienceType>(initial?.audienceType ?? 'ALL_WITH_DEVICE');
  const [scheduleKind, setScheduleKind] = useState<PushScheduleKind>(initial?.scheduleKind ?? 'NOW_ONLY');
  const [runAt, setRunAt] = useState(initial?.runAt ? isoToIstInput(initial.runAt) : '');
  const [sendTime, setSendTime] = useState(toTimeInput(initial?.sendTimeIst));
  const [endsOn, setEndsOn] = useState(initial?.endsOn ?? '');
  const [error, setError] = useState<string | null>(null);

  const audience = useQuery({
    queryKey: ['push-campaign-audience-count', audienceType],
    queryFn: () => adminPushCampaignApi.audienceCount(audienceType),
  });

  const save = useMutation({
    mutationFn: (body: PushCampaignSaveRequest) =>
      initial ? adminPushCampaignApi.update(initial.id, body) : adminPushCampaignApi.create(body),
    onSuccess: (campaign) => onSaved(campaign),
    onError: (err) => setError(apiMessage(err, 'Could not save the campaign.')),
  });

  function validate(): string | null {
    if (!name.trim() || !title.trim() || !message.trim()) return 'Name, title and message are all required.';
    if (hasDisallowedControl(title) || hasDisallowedControl(message) || hasDisallowedControl(name)) {
      return 'The text contains characters that cannot be sent.';
    }
    if (scheduleKind === 'ONCE_AT') {
      if (!runAt) return 'Pick the date and time to send.';
      if (!isInputInsideWindow(runAt)) {
        return `Scheduled sends must be between ${WINDOW_START} and ${WINDOW_END} IST. Send now can be used at any hour.`;
      }
    }
    if (scheduleKind === 'DAILY_AT') {
      if (!sendTime) return 'Pick the time of day to send.';
      if (!isInsideWindow(sendTime)) {
        return `Scheduled sends must be between ${WINDOW_START} and ${WINDOW_END} IST. Send now can be used at any hour.`;
      }
    }
    return null;
  }

  function submit() {
    const problem = validate();
    if (problem) {
      setError(problem);
      return;
    }
    setError(null);
    save.mutate({
      name: name.trim(),
      title: title.trim(),
      message: message.trim(),
      audienceType,
      scheduleKind,
      runAt: scheduleKind === 'ONCE_AT' ? istInputToIso(runAt) : null,
      sendTimeIst: scheduleKind === 'DAILY_AT' ? sendTime : null,
      endsOn: scheduleKind === 'DAILY_AT' && endsOn ? endsOn : null,
      expectedVersion: initial ? initial.version : null,
    });
  }

  const count = audience.data;
  const overLimit = count !== undefined && count.count > count.rolloutLimit;

  return (
    <FormPanel
      title={initial ? 'Edit campaign' : 'New campaign'}
      onCancel={onCancel}
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
      error={error}
      submitting={save.isPending}
      submitLabel={initial ? 'Save changes' : 'Save as draft'}
    >
      <div className="grid gap-5 lg:grid-cols-[1fr_320px]">
        <div className="space-y-4">
          <div>
            <label htmlFor={`${id}-name`} className={LABEL}>Campaign name (only you see this)</label>
            <input
              id={`${id}-name`}
              className={INPUT}
              maxLength={NAME_MAX}
              placeholder="e.g. Upload your first statement"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor={`${id}-title`} className={LABEL}>
              Notification title <span className="font-normal">({title.length}/{TITLE_MAX})</span>
            </label>
            <input
              id={`${id}-title`}
              className={INPUT}
              maxLength={TITLE_MAX}
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </div>
          <div>
            <label htmlFor={`${id}-message`} className={LABEL}>
              Message <span className="font-normal">({message.length}/{MESSAGE_MAX})</span>
            </label>
            <textarea
              id={`${id}-message`}
              className={`${INPUT} min-h-[96px]`}
              maxLength={MESSAGE_MAX}
              value={message}
              onChange={(e) => setMessage(e.target.value)}
            />
            <p className="text-xs text-muted mt-1">
              Keep it useful and operational. People can switch these off in the app, and anything that
              reads as a promotion may need legal review first.
            </p>
          </div>

          <div>
            <label htmlFor={`${id}-audience`} className={LABEL}>Who gets it</label>
            <select
              id={`${id}-audience`}
              className={INPUT}
              value={audienceType}
              onChange={(e) => setAudienceType(e.target.value as PushAudienceType)}
            >
              {(Object.keys(AUDIENCE_LABELS) as PushAudienceType[]).map((a) => (
                <option key={a} value={a}>{AUDIENCE_LABELS[a]}</option>
              ))}
            </select>
            <p className="text-xs text-muted mt-1">{AUDIENCE_HELP[audienceType]}</p>
            <p className={`text-xs mt-1 ${overLimit ? 'text-danger' : 'text-ink'}`} role="status">
              {audience.isLoading && 'Counting…'}
              {audience.isError && 'Could not count the audience.'}
              {count && (
                <>
                  About <strong>{count.count.toLocaleString('en-IN')}</strong> people right now (an estimate; it can
                  change by send time).
                  {overLimit &&
                    ` That is over the current rollout limit of ${count.rolloutLimit.toLocaleString('en-IN')}, so starting or sending will be refused until the limit is raised.`}
                </>
              )}
            </p>
          </div>

          <fieldset className="space-y-2">
            <legend className={LABEL}>When it goes out</legend>
            {([
              ['NOW_ONLY', 'Only when I press Send now'],
              ['ONCE_AT', 'Once, at a date and time'],
              ['DAILY_AT', 'Every day at a time'],
            ] as const).map(([kind, label]) => (
              <label key={kind} className="flex items-center gap-2 text-sm text-ink">
                <input
                  type="radio"
                  name={`${id}-kind`}
                  checked={scheduleKind === kind}
                  onChange={() => setScheduleKind(kind)}
                />
                {label}
              </label>
            ))}

            {scheduleKind === 'ONCE_AT' && (
              <div>
                <label htmlFor={`${id}-runAt`} className={LABEL}>Date and time (IST)</label>
                <input
                  id={`${id}-runAt`}
                  type="datetime-local"
                  className={INPUT}
                  min={`${todayIst()}T00:00`}
                  value={runAt}
                  onChange={(e) => setRunAt(e.target.value)}
                />
              </div>
            )}
            {scheduleKind === 'DAILY_AT' && (
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <label htmlFor={`${id}-time`} className={LABEL}>Time of day (IST)</label>
                  <input
                    id={`${id}-time`}
                    type="time"
                    className={INPUT}
                    value={sendTime}
                    onChange={(e) => setSendTime(e.target.value)}
                  />
                </div>
                <div>
                  <label htmlFor={`${id}-ends`} className={LABEL}>Last day (optional)</label>
                  <input
                    id={`${id}-ends`}
                    type="date"
                    className={INPUT}
                    min={todayIst()}
                    value={endsOn}
                    onChange={(e) => setEndsOn(e.target.value)}
                  />
                </div>
              </div>
            )}
            {scheduleKind !== 'NOW_ONLY' && (
              <p className="text-xs text-muted">
                Scheduled sends go out between {WINDOW_START} and {WINDOW_END} IST only. A slot that is more than
                2 hours late is skipped and shown as Missed, never sent at a bad hour.
              </p>
            )}
            <p className="text-xs text-muted">
              Nobody gets more than one campaign push a day, across all campaigns.
            </p>
          </fieldset>
        </div>

        <div aria-label="Preview">
          <p className={LABEL}>Preview</p>
          <div className="rounded-2xl border border-border bg-bg p-3 shadow-card">
            <p className="text-[11px] text-muted mb-1">Fynora · now</p>
            <p className="text-sm font-semibold text-ink break-words">{title || 'Notification title'}</p>
            <p className="text-sm text-muted whitespace-pre-line break-words">{message || 'Your message appears here.'}</p>
          </div>
          <p className="text-xs text-muted mt-2">
            Phones may cut long text short. Save, then use Send test to see it on a real phone.
          </p>
        </div>
      </div>
    </FormPanel>
  );
}
