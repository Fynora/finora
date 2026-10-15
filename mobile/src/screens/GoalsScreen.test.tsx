import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { usePreventScreenCapture } from '../lib/screenCapture';
import { GoalsScreen } from './GoalsScreen';
import { goalsApi } from '../api/endpoints';
import type { Goal } from '../types';
import { expectSkeletonNotSpinner, never } from '../test/loadingStates';
import { expectHaptics } from '../test/haptics';
import { AppAlert } from '../lib/appAlert';

jest.mock('../api/endpoints', () => ({
  goalsApi: {
    list: jest.fn(),
    create: jest.fn(),
    addContribution: jest.fn(),
    remove: jest.fn(),
  },
}));

const api = goalsApi as jest.Mocked<typeof goalsApi>;

const goal: Goal = {
  id: 'goal-1',
  name: 'Emergency fund',
  targetAmount: 100000,
  currentAmount: 40000,
  targetDate: '2027-03-01',
};

function renderScreen() {
  // gcTime 0 so the cache is collected on unmount -- see StatementHistoryScreen.test's own note.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <GoalsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

async function openContributionSheet() {
  fireEvent.press(await screen.findByLabelText('Add contribution to Emergency fund'));
  await settle();
}

/**
 * The web page collects this amount with `window.prompt()`, which does not exist in React Native --
 * the whole reason this sheet exists. These tests are the proof the substitution actually works,
 * since a missing `prompt` fails silently at runtime rather than at build time.
 */
describe('GoalsScreen — contributing to a goal', () => {
  beforeEach(() => {
    api.list.mockReset().mockResolvedValue([goal]);
    api.addContribution.mockReset().mockResolvedValue(goal);
  });

  it('opens a numeric amount field instead of a browser prompt', async () => {
    renderScreen();

    await openContributionSheet();

    const field = await screen.findByLabelText('Amount in rupees');
    // decimal-pad, not the default keyboard: this field can only ever take a number.
    expect(field.props.keyboardType).toBe('decimal-pad');
    // Names the goal being funded -- the prompt it replaces could not. Two matches: the card
    // behind the sheet, and the sheet's own subtitle.
    expect(screen.getAllByText('Emergency fund')).toHaveLength(2);
  });

  it('records the typed amount against the right goal', async () => {
    renderScreen();
    await openContributionSheet();

    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '2500');
    fireEvent.press(screen.getByText('Add'));
    await settle();

    await waitFor(() => expect(api.addContribution).toHaveBeenCalledWith('goal-1', 2500));
    expectHaptics({ success: 1 });
  });

  it('refreshes the Journey card and year in review after a contribution', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
    const invalidate = jest.spyOn(queryClient, 'invalidateQueries');
    render(<QueryClientProvider client={queryClient}><GoalsScreen /></QueryClientProvider>);
    await openContributionSheet();

    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '2500');
    fireEvent.press(screen.getByText('Add'));
    await settle();

    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['timeline'] }));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['wrapped'] });
  });

  it('refuses an amount that is not a positive number', async () => {
    renderScreen();
    await openContributionSheet();

    for (const bad of ['0', '-50', 'abc', '']) {
      fireEvent.changeText(screen.getByLabelText('Amount in rupees'), bad);
      fireEvent.press(screen.getByText('Add'));
      await settle();
    }

    // `prompt()` returned a raw string and the web page's own parse let "12abc" through as 12.
    expect(api.addContribution).not.toHaveBeenCalled();
  });

  it('keeps the sheet open with the error when the request fails', async () => {
    api.addContribution.mockReset().mockRejectedValue(
      Object.assign(new Error('failed'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'Contribution exceeds the target.' } },
      })
    );
    renderScreen();
    await openContributionSheet();

    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '999999');
    fireEvent.press(screen.getByText('Add'));
    await settle();

    expect(await screen.findByText('Contribution exceeds the target.')).toBeTruthy();
    // Still open -- closing it would lose the amount and hide why it failed.
    expect(screen.getByLabelText('Amount in rupees')).toBeTruthy();
    expectHaptics({ error: 1 });
  });

  /**
   * The request is already on its way when Cancel is tapped, so closing the sheet would let the
   * contribution land while the user last saw themselves cancel it. Money must not move after an
   * apparent cancel.
   */
  it('cannot be cancelled once the contribution is in flight', async () => {
    let release!: () => void;
    api.addContribution.mockReset().mockReturnValue(
      new Promise((resolve) => { release = () => resolve(goal); })
    );
    renderScreen();
    await openContributionSheet();

    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '2500');
    fireEvent.press(screen.getByText('Add'));
    await settle();

    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(cancel.props.accessibilityState.disabled).toBe(true);
    fireEvent.press(cancel);
    await settle();

    // Still open, still showing the request in progress.
    expect(screen.getByLabelText('Amount in rupees')).toBeTruthy();
    expect(screen.getByText('Saving…')).toBeTruthy();

    await act(async () => { release(); });
  });

  // A `submitting` flag disables the button only on the next render, so two taps dispatched in the
  // same frame both get through and the goal is funded twice.
  it('records one contribution for a double tap', async () => {
    renderScreen();
    await openContributionSheet();
    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '2500');

    const add = screen.getByText('Add');
    fireEvent.press(add);
    fireEvent.press(add);
    await settle();

    await waitFor(() => expect(api.addContribution).toHaveBeenCalledTimes(1));
  });

  it('starts empty on each open rather than inheriting the last amount', async () => {
    renderScreen();
    await openContributionSheet();
    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '2500');
    fireEvent.press(screen.getByText('Cancel'));
    await settle();

    await openContributionSheet();

    expect(screen.getByLabelText('Amount in rupees').props.value).toBe('');
  });
});

describe('GoalsScreen — creating and deleting goals', () => {
  beforeEach(() => {
    api.list.mockReset().mockResolvedValue([goal]);
    api.create.mockReset().mockResolvedValue(goal);
    api.remove.mockReset().mockResolvedValue(undefined as never);
  });

  async function openForm(fields: { name?: string; target?: string; starting?: string }) {
    renderScreen();
    fireEvent.press(await screen.findByText('+ New goal'));
    if (fields.name !== undefined) fireEvent.changeText(screen.getByLabelText('Name'), fields.name);
    if (fields.target !== undefined) fireEvent.changeText(screen.getByLabelText('Target amount'), fields.target);
    if (fields.starting !== undefined) fireEvent.changeText(screen.getByLabelText('Starting amount (optional)'), fields.starting);
  }

  it('creates a goal with one success', async () => {
    await openForm({ name: 'Holiday', target: '50000' });
    fireEvent.press(screen.getByText('Add Goal'));
    await settle();
    await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1));
    expectHaptics({ success: 1 });
  });

  it('a double press on Add Goal creates once and gives one success', async () => {
    await openForm({ name: 'Holiday', target: '50000' });
    const add = screen.getByText('Add Goal');
    fireEvent.press(add);
    fireEvent.press(add);
    await settle();
    expect(api.create).toHaveBeenCalledTimes(1);
    expectHaptics({ success: 1 });
  });

  it('a create the server refuses gives one error', async () => {
    api.create.mockRejectedValueOnce(new Error('down'));
    await openForm({ name: 'Holiday', target: '50000' });
    fireEvent.press(screen.getByText('Add Goal'));
    expect(await screen.findByText('Could not create this goal. Try again.')).toBeTruthy();
    expectHaptics({ error: 1 });
  });

  it.each([
    ['no name', { name: '', target: '50000' }, 'Give this goal a name.'],
    ['no target', { name: 'Holiday', target: '' }, 'Target amount must be a number greater than zero.'],
    ['a starting amount that is not a number', { name: 'Holiday', target: '50000', starting: 'abc' },
      'Starting amount must be a number greater than zero, or left blank.'],
  ])('a blocked send (%s) gives one warning and sends nothing', async (_case, fields, message) => {
    await openForm(fields);
    fireEvent.press(screen.getByText('Add Goal'));
    expect(await screen.findByText(message)).toBeTruthy();
    expect(api.create).not.toHaveBeenCalled();
    expectHaptics({ warning: 1 });
  });

  it('deletes a goal with one success once confirmed, and nothing on the tap that asks', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    fireEvent.press(await screen.findByLabelText('Delete Emergency fund'));
    expectHaptics({});
    const buttons = alertSpy.mock.calls[0][2]!;
    await act(async () => { buttons.find((b) => b.text === 'Delete')!.onPress!(); });
    await waitFor(() => expect(api.remove).toHaveBeenCalledWith('goal-1'));
    expectHaptics({ success: 1 });
    alertSpy.mockRestore();
  });

  it('a delete the server refuses gives one error', async () => {
    api.remove.mockRejectedValueOnce(new Error('down'));
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderScreen();
    fireEvent.press(await screen.findByLabelText('Delete Emergency fund'));
    const buttons = alertSpy.mock.calls[0][2]!;
    await act(async () => { buttons.find((b) => b.text === 'Delete')!.onPress!(); });
    expect(await screen.findByText('Could not delete this goal. Try again.')).toBeTruthy();
    expectHaptics({ error: 1 });
    alertSpy.mockRestore();
  });
});

describe('GoalsScreen — the list', () => {
  beforeEach(() => {
    api.list.mockReset().mockResolvedValue([goal]);
  });

  it('states progress in words, not only as a bar', async () => {
    renderScreen();

    // The bar itself is hidden from assistive tech; this label is the accessible representation.
    expect(
      await screen.findByLabelText(/Emergency fund: ₹40,000 of ₹1,00,000, 40 percent complete/)
    ).toBeTruthy();
  });

  // Bug fix: `pct` used to be pre-capped at 100 before reaching either the visible "X% complete"
  // text or the accessibility label -- so a goal funded past its target (e.g. 125%) displayed a
  // stuck "100% complete" everywhere. ProgressBar clamps its own fill internally, so passing it
  // the real, uncapped value (as this now does) draws the bar correctly either way.
  it('shows the real percentage past 100% for a goal funded beyond its target, not a capped "100%"', async () => {
    api.list.mockReset().mockResolvedValue([
      { ...goal, id: 'goal-2', name: 'New Laptop', targetAmount: 80000, currentAmount: 100000 },
    ]);
    renderScreen();

    expect(await screen.findByText(/125% complete/)).toBeTruthy();
    expect(screen.queryByText(/^100% complete/)).toBeNull();
    expect(
      await screen.findByLabelText(/New Laptop: ₹1,00,000 of ₹80,000, 125 percent complete/)
    ).toBeTruthy();
  });

  it('renders a target date on the day it names', async () => {
    renderScreen();

    // Not 28 Feb: a LocalDate parsed through UTC shifts a day for viewers behind UTC.
    expect(await screen.findByText(/target.*1 Mar 2027/)).toBeTruthy();
  });

  it('says so plainly when there are no goals', async () => {
    api.list.mockReset().mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText(/No goals yet/)).toBeTruthy();
  });

  // Phase 5 (Low-Priority Polish). The empty state used to say "Create one" with nothing to tap --
  // the reader had to scroll back up to find the "+ New goal" control this message was pointing at.
  it('opens the new-goal form from the empty state\'s own action', async () => {
    api.list.mockReset().mockResolvedValue([]);
    renderScreen();
    await screen.findByText(/No goals yet/);

    fireEvent.press(screen.getByText('New goal'));

    expect(await screen.findByLabelText('Name')).toBeTruthy();
  });
});

// D3 (Track D security cleanup). Savings-goal targets/progress are as screenshot-attractive as
// anything on the Dashboard or Accounts screen, which already guard against this.
describe('screen capture protection (Track D/D3)', () => {
  it('calls usePreventScreenCapture on mount', () => {
    renderScreen();

    expect(usePreventScreenCapture).toHaveBeenCalled();
  });
});

describe('GoalsScreen first load', () => {
  it('shows the goals skeleton, not a spinner, on first load', () => {
    api.list.mockReturnValue(never());
    renderScreen();
    expectSkeletonNotSpinner();
    expect(screen.getByTestId('goals-skeleton')).toBeTruthy();
  });
});
