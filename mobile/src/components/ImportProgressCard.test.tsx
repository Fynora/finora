import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { ImportProgressCard } from './ImportProgressCard';
import { importJobsApi, type ImportJobProgress } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  importJobsApi: { progress: jest.fn(), timeline: jest.fn(), cancel: jest.fn() },
}));

const api = importJobsApi as jest.Mocked<typeof importJobsApi>;

function jobProgress(over: Partial<ImportJobProgress> = {}): ImportJobProgress {
  return {
    jobId: 'job-1', fileName: 'statement.csv', status: 'QUEUED', userStatus: 'PROCESSING',
    rowsTotal: null, rowsProcessed: 0, createdAt: '2026-09-08T00:00:00Z', startedAt: null,
    finishedAt: null, importSessionId: null, error: null, correlationId: null, ...over,
  };
}

const onReady = jest.fn();
const onGaveUp = jest.fn();
const onDismiss = jest.fn();

function renderCard() {
  return render(
    <ThemeProvider>
      <ImportProgressCard jobId="job-1" onReady={onReady} onGaveUp={onGaveUp} onDismiss={onDismiss} />
    </ThemeProvider>
  );
}

beforeEach(() => {
  onReady.mockReset();
  onGaveUp.mockReset();
  onDismiss.mockReset();
  api.progress.mockReset();
  api.timeline.mockReset();
  api.cancel.mockReset();
});

/**
 * Phase 4 (Medium-Tier Parity). Real (not faked) timers, matching ImportScreen.test.tsx's own
 * "upload completion dwell" convention -- ImportProgressCard's poll schedule starts at 100ms, so
 * `waitFor`'s default polling interval catches the first tick well inside its own default timeout.
 */
describe('ImportProgressCard', () => {
  it('shows "Uploading" before the first poll lands', () => {
    api.progress.mockReturnValue(new Promise(() => {})); // never resolves in this test
    renderCard();

    expect(screen.getByText('Uploading')).toBeTruthy();
  });

  it('polls and renders the label the job reports', async () => {
    api.progress.mockResolvedValue(jobProgress({ status: 'PARSING' }));
    renderCard();

    expect(await screen.findByText('Reading your statement')).toBeTruthy();
  });

  it('calls onReady with the session once the job completes', async () => {
    api.progress.mockResolvedValue(
      jobProgress({ status: 'COMPLETED', userStatus: 'COMPLETED', rowsTotal: 3, rowsProcessed: 3, importSessionId: 'session-1' })
    );
    renderCard();

    await waitFor(() => expect(onReady).toHaveBeenCalledWith('session-1'));
    expect(onGaveUp).not.toHaveBeenCalled();
  });

  it('calls onGaveUp, not onReady, once the job is cancelled', async () => {
    api.progress.mockResolvedValue(jobProgress({ status: 'CANCELLED', userStatus: 'CANCELLED' }));
    renderCard();

    await waitFor(() => expect(onGaveUp).toHaveBeenCalledWith(expect.objectContaining({ status: 'CANCELLED' })));
    expect(onReady).not.toHaveBeenCalled();
  });

  it('shows the held-for-review message and still calls onGaveUp, leaving the caller to decide whether to stay mounted', async () => {
    api.progress.mockResolvedValue(jobProgress({ status: 'HELD_FOR_REVIEW', userStatus: 'HELD_FOR_REVIEW' }));
    renderCard();

    expect(await screen.findByText('Running additional checks')).toBeTruthy();
    await waitFor(() => expect(onGaveUp).toHaveBeenCalledWith(expect.objectContaining({ status: 'HELD_FOR_REVIEW' })));
  });

  // Bug fix, caught by this test failing before the fix: the polling-schedule `stopped` flag was
  // flipped true (by stop()) BEFORE the one-shot timeline() fetch below even started, so guarding
  // that fetch's own callback on the same flag meant it always saw `stopped === true` and silently
  // dropped the curated reason on every single FAILED job. A separate `unmounted` flag, set only
  // on the effect's real cleanup, fixes it.
  it('fetches and shows the curated failure reason once the job fails', async () => {
    api.progress.mockResolvedValue(jobProgress({ status: 'FAILED', userStatus: 'FAILED', error: 'raw internal text' }));
    api.timeline.mockResolvedValue({
      jobId: 'job-1', status: 'FAILED', userStatus: 'FAILED', failureCode: 'IMPORT_001', stages: [],
    });
    renderCard();

    expect(await screen.findByText(/couldn't find a transaction table/i)).toBeTruthy();
    // job.error is raw, untranslated text -- never fit to show a user directly (see
    // ErrorCode's own doc comment on the backend); only the curated reason reaches the screen.
    expect(screen.queryByText('raw internal text')).toBeNull();
  });

  it('shows no curated reason when the failure code has none in the table, without crashing', async () => {
    api.progress.mockResolvedValue(jobProgress({ status: 'FAILED', userStatus: 'FAILED' }));
    api.timeline.mockResolvedValue({
      jobId: 'job-1', status: 'FAILED', userStatus: 'FAILED', failureCode: 'SOME_UNMAPPED_CODE', stages: [],
    });
    renderCard();

    expect(await screen.findByText("Couldn't finish")).toBeTruthy();
  });

  it('calls onDismiss from the "Choose a different file" link, only once the job has failed', async () => {
    api.progress.mockResolvedValue(jobProgress({ status: 'FAILED', userStatus: 'FAILED' }));
    api.timeline.mockResolvedValue({
      jobId: 'job-1', status: 'FAILED', userStatus: 'FAILED', failureCode: null, stages: [],
    });
    renderCard();

    fireEvent.press(await screen.findByText('Choose a different file'));

    expect(onDismiss).toHaveBeenCalled();
  });

  it('offers no dismiss link while the job is still in flight', () => {
    api.progress.mockReturnValue(new Promise(() => {}));
    renderCard();

    expect(screen.queryByText('Choose a different file')).toBeNull();
  });

  it('offers Cancel before IMPORTING, and calls importJobsApi.cancel', async () => {
    api.progress.mockResolvedValue(jobProgress({ status: 'ANALYZING' }));
    api.cancel.mockResolvedValue(jobProgress({ status: 'CANCELLED', userStatus: 'CANCELLED' }));
    renderCard();
    await screen.findByText('Finding the transactions');

    fireEvent.press(screen.getByText('Cancel'));
    await act(async () => {});

    expect(api.cancel).toHaveBeenCalledWith('job-1');
    expect(onGaveUp).toHaveBeenCalledWith(expect.objectContaining({ status: 'CANCELLED' }));
  });

  it('hides Cancel once the job reaches IMPORTING -- financial rows already exist by then', async () => {
    api.progress.mockResolvedValue(jobProgress({ status: 'IMPORTING' }));
    renderCard();

    await screen.findByText('Adding them to your account');
    expect(screen.queryByText('Cancel')).toBeNull();
  });
});
