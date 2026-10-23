import { StrictMode } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import Insights from './Insights';
import {
  aiInsightsApi, categoriesApi, insightsApi, peopleApi, recurringApi, onboardingApi, usageApi,
  type AiStory, type InsightsData, type RecurringItem,
} from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  insightsApi: {
    get: vi.fn(),
    acknowledgements: vi.fn().mockResolvedValue([]), acknowledge: vi.fn().mockResolvedValue(undefined),
  },
  // The month story (AI insights spec §8.4). The beforeEach below puts "nothing to show" back before
  // every test, so a story one test sets up never leaks into the next.
  aiInsightsApi: { story: vi.fn(), generateStory: vi.fn(), feedback: vi.fn() },
  // Shared payments and loans: nothing to ask and nobody owing unless a test says so.
  peopleApi: {
    suggestions: vi.fn().mockResolvedValue({ items: [], expected: [] }), list: vi.fn().mockResolvedValue([]),
    addEntry: vi.fn(), settle: vi.fn(), addSplitRule: vi.fn(), link: vi.fn(), preferences: vi.fn(), setOwnUpiId: vi.fn(),
  },
  inflowApi: { kinds: vi.fn().mockResolvedValue([]), setChoice: vi.fn() },
  transactionsApi: { search: vi.fn(), updateCategory: vi.fn() },
  recurringApi: { list: vi.fn(), dismiss: vi.fn(), confirm: vi.fn(), categorize: vi.fn(), changedAmounts: vi.fn().mockResolvedValue([]) },
  // Getting-started checklist dwell timer (D-onboarding) -- default to "no VIEW_INSIGHTS item in
  // the response" so it never fires in tests that don't care about it; the dwell-timer's own
  // tests override this.
  onboardingApi: {
    getChecklist: vi.fn().mockResolvedValue({ items: [], completedCount: 0, totalCount: 6 }),
    completeChecklistItem: vi.fn().mockResolvedValue(undefined),
  },
  usageApi: {
    recordView: vi.fn().mockResolvedValue(undefined),
  },
  // Cosmetic icon/color lookup for Category Movers/Top Merchant rows -- defaults to empty so
  // every existing test (which doesn't care about icons) degrades to the 'tag'/'gray' fallback
  // already built into Insights.tsx's iconFor/colorFor, same as a real failed fetch would.
  categoriesApi: { list: vi.fn().mockResolvedValue([]) },
}));
// The UPI request names the user; every page sits inside AuthProvider in the app.
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ fullName: 'Sample User' }) }));

// Every figure in a story here is invented.
function story(overrides: Partial<AiStory> = {}): AiStory {
  return {
    status: 'none', id: null, text: null, writtenByAi: false, basis: null, canGenerate: false,
    rating: null, ratingReason: null, ...overrides,
  };
}
const AI_STORY = story({
  status: 'ready', id: 'story-1', text: 'Food took ₹8,000 this month, ₹3,000 more than your usual month.',
  writtenByAi: true, basis: 'Compared with your usual month (Jun–Aug)',
});

beforeEach(() => {
  vi.mocked(aiInsightsApi.story).mockReset().mockResolvedValue(story());
  vi.mocked(aiInsightsApi.generateStory).mockReset().mockResolvedValue(story());
  vi.mocked(aiInsightsApi.feedback).mockReset().mockResolvedValue(undefined);
  vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([]);
});

function insights(overrides: Partial<InsightsData> = {}): InsightsData {
  return {
    sentences: ['You spent more on Food this month than usual.'],
    movers: [{ category: 'Food', current: 8000, priorAverage: 5000, pctChange: 60 }],
    biggestCategory: null,
    topMerchant: null,
    ...overrides,
  };
}

function recurringItem(overrides: Partial<RecurringItem> = {}): RecurringItem {
  return {
    merchant: 'netflix',
    label: 'Monthly',
    averageAmount: 649,
    occurrences: 4,
    lastDate: '2026-08-01',
    nextEstimate: '2026-09-01',
    ...overrides,
  };
}

/** A promise that never settles -- "this endpoint is still in flight". */
function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

// Insights now reads useQueryClient() (for the checklist-invalidation bug fix on the dwell
// timer below) -- every render needs a real QueryClientProvider ancestor, same pattern
// Ledger.test.tsx's own renderLedger() already uses.
function renderInsights() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Insights />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('Insights — section-scoped loading', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  /**
   * The page used to await Promise.all([insightsApi.get(), recurringApi.list()]) behind ONE
   * `loading` flag, so the whole page waited on the slower of two endpoints that have no dependency
   * on each other. §1's rule: section-scoped loading whenever sections are independently sourced.
   */
  it('renders the Recurring card as soon as /recurring resolves, without waiting on /insights', async () => {
    vi.mocked(insightsApi.get).mockReturnValue(pending<InsightsData>());
    vi.mocked(recurringApi.list).mockResolvedValue([recurringItem()]);

    renderInsights();

    expect(await screen.findByText(/netflix/i)).toBeInTheDocument();
    // ...while the other two cards are still, correctly, loading.
    expect(screen.getByText('Loading your observations')).toBeInTheDocument();
    expect(screen.getByText('Loading category movers')).toBeInTheDocument();
  });

  it('asks what an unanswered repeating payment is, under its row', async () => {
    vi.mocked(insightsApi.get).mockReturnValue(pending<InsightsData>());
    vi.mocked(recurringApi.list).mockResolvedValue([recurringItem({
      merchant: 'sample owner', averageAmount: 10000, state: 'NEEDS_ANSWER', answer: null, latestAmount: 10000,
    })]);

    renderInsights();

    expect(await screen.findByText('What is this ₹10,000 monthly payment?')).toBeInTheDocument();
  });

  it('still asks "still Rent?" for a payee whose amount moved when no group is detected at all', async () => {
    vi.mocked(insightsApi.get).mockReturnValue(pending<InsightsData>());
    vi.mocked(recurringApi.list).mockResolvedValue([]);
    vi.mocked(recurringApi.changedAmounts).mockResolvedValueOnce([
      { merchant: 'sample owner', category: 'Rent', latestAmount: 12500, latestDate: '2026-07-03', amountMin: 7999, amountMax: 12001 },
    ]);

    renderInsights();

    expect(await screen.findByText('₹12,500 to sample owner — still Rent?')).toBeInTheDocument();
  });

  it('renders Observations and Movers as soon as /insights resolves, without waiting on /recurring', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
    vi.mocked(recurringApi.list).mockReturnValue(pending<RecurringItem[]>());

    renderInsights();

    expect(await screen.findByText(/You spent more on Food/)).toBeInTheDocument();
    expect(screen.getByText('Loading recurring payments')).toBeInTheDocument();
  });

  /**
   * The sharper half of the same bug: `error` was shared, so ONE failing endpoint replaced the whole
   * page with a single line of text -- including the sections whose own fetch had succeeded.
   */
  it('keeps Observations and Movers when /recurring is the endpoint that failed', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
    vi.mocked(recurringApi.list).mockRejectedValue(new Error('boom'));

    renderInsights();

    expect(await screen.findByText(/Couldn't load your recurring payments/)).toBeInTheDocument();
    expect(screen.getByText(/You spent more on Food/)).toBeInTheDocument();
    expect(screen.getByText('Food')).toBeInTheDocument();
  });

  it('keeps the Recurring card when /insights is the endpoint that failed', async () => {
    vi.mocked(insightsApi.get).mockRejectedValue(new Error('boom'));
    vi.mocked(recurringApi.list).mockResolvedValue([recurringItem()]);

    renderInsights();

    expect(await screen.findByText(/netflix/i)).toBeInTheDocument();
    expect(screen.getAllByText(/Couldn't load your insights/)).toHaveLength(2);
  });

  /**
   * The accessibility contract that makes this swap an improvement rather than a regression: the
   * page previously rendered readable "Loading…" text, so replacing it with aria-hidden shapes and
   * no announcing region would be strictly worse. The Region must be present IMMEDIATELY, before
   * useDelayedLoading's anti-flash window has elapsed and any shape has rendered.
   */
  it('announces each loading section immediately, before any skeleton shape appears', () => {
    vi.mocked(insightsApi.get).mockReturnValue(pending<InsightsData>());
    vi.mocked(recurringApi.list).mockReturnValue(pending<RecurringItem[]>());

    renderInsights();

    const regions = screen.getAllByRole('status');
    expect(regions).toHaveLength(3);
    regions.forEach((r) => expect(r).toHaveAttribute('aria-busy', 'true'));
    expect(screen.getByText('Loading your observations')).toBeInTheDocument();
    expect(screen.getByText('Loading recurring payments')).toBeInTheDocument();
    expect(screen.getByText('Loading category movers')).toBeInTheDocument();
  });
});

describe('Insights — getting-started checklist dwell timer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
    vi.mocked(recurringApi.list).mockResolvedValue([]);
  });

  it('marks VIEW_INSIGHTS complete after a 1.5s dwell', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [{ key: 'VIEW_INSIGHTS', completed: false }], completedCount: 0, totalCount: 6,
    });
    const completeSpy = vi.mocked(onboardingApi.completeChecklistItem).mockResolvedValue(undefined as any);

    renderInsights();

    // Flushes the getChecklist().then(setChecklist) microtask (and the re-render/effect it
    // triggers) before advancing to the dwell timer itself -- vi.waitFor's own polling is timer-
    // based and deadlocks against fake timers, so this uses advanceTimersByTimeAsync(0) instead.
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1500);

    expect(completeSpy).toHaveBeenCalledWith('VIEW_INSIGHTS');
    vi.useRealTimers();
  });

  it('does not fire if the item is already complete', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [{ key: 'VIEW_INSIGHTS', completed: true }], completedCount: 1, totalCount: 6,
    });
    const completeSpy = vi.mocked(onboardingApi.completeChecklistItem).mockResolvedValue(undefined as any);

    renderInsights();

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1500);

    expect(completeSpy).not.toHaveBeenCalled();
    vi.useRealTimers();
  });
});

describe('Insights — Smart Insights view tracking', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
    vi.mocked(recurringApi.list).mockResolvedValue([]);
  });

  it('records a real view after a 1.5s dwell, so a bounce is not counted', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });

    renderInsights();

    await vi.advanceTimersByTimeAsync(0);
    expect(usageApi.recordView).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1500);
    expect(usageApi.recordView).toHaveBeenCalledWith('insights');
    vi.useRealTimers();
  });

  it('does not record a view if unmounted before the dwell elapses', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });

    const { unmount } = renderInsights();
    await vi.advanceTimersByTimeAsync(500);
    unmount();
    await vi.advanceTimersByTimeAsync(1500);

    expect(usageApi.recordView).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  // The distinguishing behavior from the getting-started checklist's VIEW_INSIGHTS item just
  // above: that one fires once ever (guarded by item.completed), this fires on every real visit
  // -- a running counter, not a one-time flag. A regression that accidentally copied the
  // checklist's "once" guard onto this effect would still pass every other test in this file.
  it('records another view on a second visit, unlike the once-ever checklist item', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });

    const { unmount } = renderInsights();
    await vi.advanceTimersByTimeAsync(1500);
    unmount();
    renderInsights();
    await vi.advanceTimersByTimeAsync(1500);

    expect(usageApi.recordView).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });
});

describe('Insights — Top Merchant highlight', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(recurringApi.list).mockResolvedValue([]);
  });

  it('shows the top merchant, name and amount, whether or not there is an AI story', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(
      insights({ topMerchant: { name: 'Myntra', amount: 3299 } })
    );
    vi.mocked(aiInsightsApi.story).mockResolvedValue(AI_STORY);
    renderInsights();

    expect(await screen.findByText(/Myntra/)).toBeInTheDocument();
    expect(screen.getByText(/₹3,299/)).toBeInTheDocument();
  });

  it('renders no top merchant row when the field is null', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights({ topMerchant: null }));
    renderInsights();

    await screen.findByText(/You spent more on Food/);
    expect(screen.queryByText(/Your top merchant/)).not.toBeInTheDocument();
  });

  // Statements are imported in arrears: the insights' month (newest with a purchase) is routinely
  // not the current one, and the response now says which it is.
  it('names the month the top merchant is from when it is not the current one', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights({
      topMerchant: { name: 'Myntra', amount: 3299 }, reportingMonth: '2026-06', reportingMonthIsCurrent: false,
    }));
    renderInsights();

    expect(await screen.findByText(/Your top merchant in June 2026 was/)).toBeInTheDocument();
    expect(screen.queryByText(/top merchant this month/)).not.toBeInTheDocument();
  });

  it('keeps "this month" when the server says the month is current', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(insights({
      topMerchant: { name: 'Myntra', amount: 3299 }, reportingMonth: '2026-10', reportingMonthIsCurrent: true,
    }));
    renderInsights();

    expect(await screen.findByText(/Your top merchant this month was/)).toBeInTheDocument();
  });
});

describe('Insights — Category Movers emphasis', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(recurringApi.list).mockResolvedValue([]);
  });

  // 60% clears InsightsService's own MOVER_SIGNIFICANCE_THRESHOLD_PCT (15) -- reused here so a
  // mover's badge and its sentence agree on what counts as significant.
  it('badges a mover past the significance threshold as Up', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(
      insights({ movers: [{ category: 'Food', current: 8000, priorAverage: 5000, pctChange: 60 }] })
    );
    renderInsights();

    await screen.findByText('Food');
    expect(screen.getByText('Up')).toBeInTheDocument();
  });

  it('badges a significant decrease as Down', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(
      insights({ movers: [{ category: 'Travel', current: 1000, priorAverage: 5000, pctChange: -80 }] })
    );
    renderInsights();

    await screen.findByText('Travel');
    expect(screen.getByText('Down')).toBeInTheDocument();
  });

  it('shows no badge for a mover under the significance threshold', async () => {
    vi.mocked(insightsApi.get).mockResolvedValue(
      insights({ movers: [{ category: 'Utilities', current: 2100, priorAverage: 2000, pctChange: 5 }] })
    );
    renderInsights();

    await screen.findByText('Utilities');
    expect(screen.queryByText('Up')).not.toBeInTheDocument();
    expect(screen.queryByText('Down')).not.toBeInTheDocument();
  });
});

describe('Insights — Recurring Payments dismiss', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
  });

  it('dismisses a wrongly-detected group and removes it from the list', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([recurringItem({ merchant: 'netflix' })]);
    vi.mocked(recurringApi.dismiss).mockResolvedValue(undefined);
    renderInsights();
    await screen.findByText('netflix');

    await userEvent.click(screen.getByRole('button', { name: 'Not recurring: dismiss netflix' }));

    expect(recurringApi.dismiss).toHaveBeenCalledWith('netflix');
    await waitFor(() => expect(screen.queryByText('netflix')).not.toBeInTheDocument());
  });
});

describe('Insights — AI month story', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
    vi.mocked(recurringApi.list).mockResolvedValue([]);
  });

  const RULE_SENTENCE = /You spent more on Food/;

  it('storyShowsWithLabelAndBasis: a stored AI story leads, with its label, basis and thumbs', async () => {
    vi.mocked(aiInsightsApi.story).mockResolvedValue(AI_STORY);

    renderInsights();

    expect(await screen.findByText(AI_STORY.text!)).toBeInTheDocument();
    expect(screen.getByText('Written by AI')).toBeInTheDocument();
    expect(screen.getByText(AI_STORY.basis!)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Helpful' })).toBeInTheDocument();
    // The old "Fyn:" label is gone.
    expect(screen.queryByText('Fyn:')).not.toBeInTheDocument();
    // A stored story is only read: the page asks the server to write nothing.
    expect(aiInsightsApi.generateStory).not.toHaveBeenCalled();
    // The banner no longer calls the whole card rule-based.
    expect(screen.getByText(/Text marked "Written by AI" is written by AI from your own figures/)).toBeInTheDocument();
  });

  it('collapses the rule-based sentences behind a toggle under an AI story, reveals them on click', async () => {
    vi.mocked(aiInsightsApi.story).mockResolvedValue(AI_STORY);

    renderInsights();
    await screen.findByText(AI_STORY.text!);

    expect(screen.queryByText(RULE_SENTENCE)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'View the numbers' }));
    expect(screen.getByText(RULE_SENTENCE)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hide the numbers' })).toBeInTheDocument();
  });

  it('noStoryKeepsTheRulesSentences: no story, rules text, or a failed read all keep the sentences', async () => {
    // Nothing to show.
    const first = renderInsights();
    expect(await screen.findByText(RULE_SENTENCE)).toBeInTheDocument();
    first.unmount();

    // Rules text: the page's own sentences already say it, so the card shows those, unlabelled.
    vi.mocked(aiInsightsApi.story).mockResolvedValue(story({ status: 'ready', text: 'Rules line for the month.' }));
    const second = renderInsights();
    expect(await screen.findByText(RULE_SENTENCE)).toBeInTheDocument();
    second.unmount();

    // The read fails.
    vi.mocked(aiInsightsApi.story).mockRejectedValue(new Error('offline'));
    renderInsights();
    expect(await screen.findByText(RULE_SENTENCE)).toBeInTheDocument();

    await waitFor(() => expect(aiInsightsApi.story).toHaveBeenCalledTimes(3));
    expect(screen.queryByText('Rules line for the month.')).not.toBeInTheDocument();
    expect(screen.queryByText('Written by AI')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'View the numbers' })).not.toBeInTheDocument();
    expect(screen.getByText(/These are rule-based statistical observations/)).toBeInTheDocument();
    expect(aiInsightsApi.generateStory).not.toHaveBeenCalled();
  });

  it('shows the sentences, with no toggle, while the story is still being read', async () => {
    vi.mocked(aiInsightsApi.story).mockReturnValue(pending<AiStory>());

    renderInsights();

    expect(await screen.findByText(RULE_SENTENCE)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'View the numbers' })).not.toBeInTheDocument();
  });

  it('asks the server to write the story when it may, and shows what comes back', async () => {
    vi.mocked(aiInsightsApi.story).mockResolvedValue(story({ canGenerate: true }));
    vi.mocked(aiInsightsApi.generateStory).mockResolvedValue(AI_STORY);

    renderInsights();

    expect(await screen.findByText(AI_STORY.text!)).toBeInTheDocument();
    // The newest reporting month: the same month the page's own figures describe.
    expect(aiInsightsApi.generateStory).toHaveBeenCalledWith();
    expect(aiInsightsApi.story).toHaveBeenCalledWith();
  });

  it('writes a new story when the stored one was written for older figures, and shows the new one', async () => {
    // GET /story serves the newest story it has while a new one may be written (canGenerate).
    vi.mocked(aiInsightsApi.story).mockResolvedValue({ ...AI_STORY, canGenerate: true });
    vi.mocked(aiInsightsApi.generateStory).mockResolvedValue({ ...AI_STORY, id: 'story-2', text: 'Rewritten for the new figures.' });

    renderInsights();

    expect(await screen.findByText('Rewritten for the new figures.')).toBeInTheDocument();
    expect(screen.queryByText(AI_STORY.text!)).not.toBeInTheDocument();
    expect(aiInsightsApi.generateStory).toHaveBeenCalledTimes(1);
  });

  it('keeps showing the stored story when writing a new one fails', async () => {
    vi.mocked(aiInsightsApi.story).mockResolvedValue({ ...AI_STORY, canGenerate: true });
    vi.mocked(aiInsightsApi.generateStory).mockRejectedValue(new Error('server error'));

    renderInsights();

    await waitFor(() => expect(aiInsightsApi.generateStory).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(AI_STORY.text!)).toBeInTheDocument();
  });

  it('keeps the sentences when writing the story fails', async () => {
    vi.mocked(aiInsightsApi.story).mockResolvedValue(story({ canGenerate: true }));
    vi.mocked(aiInsightsApi.generateStory).mockRejectedValue(new Error('server error'));

    renderInsights();

    await waitFor(() => expect(aiInsightsApi.generateStory).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(RULE_SENTENCE)).toBeInTheDocument();
    expect(screen.queryByText('Written by AI')).not.toBeInTheDocument();
  });

  it('postsAtMostOnce: one read and one write even under React.StrictMode double-invocation', async () => {
    // Writing a story spends real model cost. StrictMode (used in main.tsx) runs effects twice in
    // development; the mount-scoped guard must stop the second run.
    vi.mocked(aiInsightsApi.story).mockResolvedValue(story({ canGenerate: true }));
    vi.mocked(aiInsightsApi.generateStory).mockResolvedValue(AI_STORY);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <StrictMode>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter>
            <Insights />
          </MemoryRouter>
        </QueryClientProvider>
      </StrictMode>
    );

    expect(await screen.findByText(AI_STORY.text!)).toBeInTheDocument();
    expect(aiInsightsApi.story).toHaveBeenCalledTimes(1);
    expect(aiInsightsApi.generateStory).toHaveBeenCalledTimes(1);
  });

  describe('while another request is writing it', () => {
    beforeEach(() => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    it('reads it again 2 s later and shows it once ready, without writing again', async () => {
      vi.mocked(aiInsightsApi.story)
        .mockResolvedValueOnce(story({ canGenerate: true }))
        .mockResolvedValueOnce(AI_STORY);
      vi.mocked(aiInsightsApi.generateStory).mockResolvedValue(story({ status: 'generating' }));

      renderInsights();
      await waitFor(() => expect(aiInsightsApi.generateStory).toHaveBeenCalledTimes(1));
      expect(aiInsightsApi.story).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(2000);
      expect(await screen.findByText(AI_STORY.text!)).toBeInTheDocument();
      expect(aiInsightsApi.story).toHaveBeenCalledTimes(2);
      expect(aiInsightsApi.generateStory).toHaveBeenCalledTimes(1);
    });

    it('gives up after two more reads and keeps the sentences', async () => {
      vi.mocked(aiInsightsApi.story).mockResolvedValue(story({ status: 'generating' }));

      renderInsights();
      await waitFor(() => expect(aiInsightsApi.story).toHaveBeenCalledTimes(1));
      await vi.advanceTimersByTimeAsync(2000);
      await waitFor(() => expect(aiInsightsApi.story).toHaveBeenCalledTimes(2));
      await vi.advanceTimersByTimeAsync(2000);
      await waitFor(() => expect(aiInsightsApi.story).toHaveBeenCalledTimes(3));
      await vi.advanceTimersByTimeAsync(10000);

      expect(aiInsightsApi.story).toHaveBeenCalledTimes(3);
      expect(aiInsightsApi.generateStory).not.toHaveBeenCalled();
      expect(screen.getByText(RULE_SENTENCE)).toBeInTheDocument();
    });

    it('stops reading once the page is closed', async () => {
      vi.mocked(aiInsightsApi.story).mockResolvedValue(story({ status: 'generating' }));

      const view = renderInsights();
      await waitFor(() => expect(aiInsightsApi.story).toHaveBeenCalledTimes(1));
      view.unmount();
      await vi.advanceTimersByTimeAsync(10000);

      expect(aiInsightsApi.story).toHaveBeenCalledTimes(1);
    });
  });

  it('saves a thumb on the story by its id, and shows a thumb saved earlier', async () => {
    vi.mocked(aiInsightsApi.story).mockResolvedValue({ ...AI_STORY, rating: 'NOT_HELPFUL', ratingReason: 'CONFUSING' });

    renderInsights();
    await screen.findByText(AI_STORY.text!);
    expect(screen.getByRole('button', { name: 'Not helpful' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Thanks for telling us.')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Helpful' }));
    await waitFor(() => expect(aiInsightsApi.feedback).toHaveBeenCalledWith('story-1', 'HELPFUL', null));
  });
});

describe('Insights: money with people', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(insightsApi.get).mockResolvedValue(insights());
    vi.mocked(recurringApi.list).mockResolvedValue([]);
  });

  it('asks about money with people, and re-reads its figures once an answer is saved', async () => {
    vi.mocked(peopleApi.suggestions).mockResolvedValue({
      items: [{
        kind: 'LENT_CHECK', ackKey: 'd-1', txnId: 'd-1', relatedTxnId: null, personKey: 'vpa:friend.k', label: 'Friend K',
        amount: 2000, date: '2026-08-03', category: null, bills: [], owed: null,
      }],
      expected: [],
    });
    vi.mocked(peopleApi.addEntry).mockResolvedValue({} as never);
    renderInsights();
    expect(await screen.findByRole('heading', { name: 'Money with people' })).toBeInTheDocument();
    expect(insightsApi.get).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Lent to Friend K' }));
    await waitFor(() => expect(insightsApi.get).toHaveBeenCalledTimes(2));
    expect(recurringApi.list).toHaveBeenCalledTimes(2);
  });

  function lentQuestion() {
    vi.mocked(peopleApi.suggestions).mockResolvedValue({
      items: [{
        kind: 'LENT_CHECK', ackKey: 'd-1', txnId: 'd-1', relatedTxnId: null, personKey: 'vpa:friend.k', label: 'Friend K',
        amount: 2000, date: '2026-08-03', category: null, bills: [], owed: null,
      }],
      expected: [],
    });
    vi.mocked(peopleApi.addEntry).mockResolvedValue({} as never);
  }

  // An answer moves the month's figures, so the story written from them is read again, and
  // rewritten when the server allows it.
  it('reads the story again after an answer, and shows the one written for the new figures', async () => {
    lentQuestion();
    vi.mocked(aiInsightsApi.story)
      .mockResolvedValueOnce(AI_STORY)
      .mockResolvedValueOnce({ ...AI_STORY, canGenerate: true });
    vi.mocked(aiInsightsApi.generateStory).mockResolvedValue({ ...AI_STORY, id: 'story-2', text: 'Rewritten after the answer.' });
    renderInsights();
    expect(await screen.findByText(AI_STORY.text!)).toBeInTheDocument();
    expect(aiInsightsApi.generateStory).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Lent to Friend K' }));

    expect(await screen.findByText('Rewritten after the answer.')).toBeInTheDocument();
    expect(aiInsightsApi.story).toHaveBeenCalledTimes(2);
    expect(aiInsightsApi.generateStory).toHaveBeenCalledTimes(1);
  });

  it('takes the story away when the read after an answer finds none', async () => {
    lentQuestion();
    vi.mocked(aiInsightsApi.story).mockResolvedValueOnce(AI_STORY).mockResolvedValueOnce(story());
    renderInsights();
    expect(await screen.findByText(AI_STORY.text!)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Lent to Friend K' }));

    await waitFor(() => expect(screen.queryByText(AI_STORY.text!)).not.toBeInTheDocument());
    expect(screen.getByText(/You spent more on Food/)).toBeInTheDocument();
  });

  it('ignores an earlier read that answers after a later one', async () => {
    lentQuestion();
    let answerFirst: (s: AiStory) => void = () => {};
    vi.mocked(aiInsightsApi.story)
      .mockReturnValueOnce(new Promise<AiStory>((resolve) => { answerFirst = resolve; }))
      .mockResolvedValueOnce(story());
    renderInsights();
    await userEvent.click(await screen.findByRole('button', { name: 'Lent to Friend K' }));
    await waitFor(() => expect(aiInsightsApi.story).toHaveBeenCalledTimes(2));

    answerFirst(AI_STORY);
    await new Promise((r) => setTimeout(r, 20));

    expect(screen.queryByText(AI_STORY.text!)).not.toBeInTheDocument();
  });

  // Answering a repeating payment re-files its past payments (RecurringAnswerService), which moves
  // the month's figures just as a people answer does.
  it('re-reads the figures and the story after a repeating-payment answer', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([recurringItem({
      merchant: 'sample owner', averageAmount: 10000, state: 'NEEDS_ANSWER', answer: null, latestAmount: 10000,
    })]);
    vi.mocked(categoriesApi.list).mockResolvedValue(
      [{ id: 'cat-1', name: 'Rent', isSystem: true, icon: 'tag', color: 'gray' }] as never,
    );
    vi.mocked(recurringApi.categorize).mockResolvedValue(undefined);
    renderInsights();

    await userEvent.click(await screen.findByRole('button', { name: 'Rent' }));

    await waitFor(() => expect(insightsApi.get).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(aiInsightsApi.story).toHaveBeenCalledTimes(2));
  });

  it('lists who owes whom under With people', async () => {
    vi.mocked(peopleApi.list).mockResolvedValue([
      { personKey: 'vpa:friend.k', label: 'Friend K', owedToUser: 500, since: '2026-08-03', family: false },
    ]);
    renderInsights();
    expect(await screen.findByRole('link', { name: 'Friend K owes you ₹500' })).toBeInTheDocument();
  });

  it('shows a repeating bill other people share at the user\'s share, and how many share it', async () => {
    vi.mocked(recurringApi.list).mockResolvedValue([
      recurringItem({ merchant: 'sample owner', averageAmount: 36000, yourShare: 9000, sharedWith: 3 }),
    ]);
    renderInsights();
    expect(await screen.findByText('Your share ₹9,000 of ₹36,000 · split with 3 · 4x seen')).toBeInTheDocument();
  });
});
