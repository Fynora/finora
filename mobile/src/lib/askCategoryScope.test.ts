import { askCategoryScope } from './askCategoryScope';
import { dismissCurrentAppAlert, dismissCurrentAppAlertByUser, getCurrentAppAlert } from './appAlert';
import { transactionsApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  transactionsApi: { similar: jest.fn() },
}));

const similar = transactionsApi.similar as jest.Mock;

/** Waits for the alert askCategoryScope queues once its lookup has resolved. */
async function shownAlert() {
  for (let i = 0; i < 20 && !getCurrentAppAlert(); i++) await Promise.resolve();
  const alert = getCurrentAppAlert();
  if (!alert) throw new Error('no alert was shown');
  return alert;
}

function press(text: string) {
  const alert = getCurrentAppAlert()!;
  const button = alert.buttons.find((b) => b.text === text)!;
  dismissCurrentAppAlert();
  button.onPress?.();
}

afterEach(() => {
  while (getCurrentAppAlert()) dismissCurrentAppAlert();
});

describe('askCategoryScope', () => {
  it('asks nothing when no other transaction is from the payee', async () => {
    similar.mockResolvedValue({ similar: 0, keptByUser: 3 });

    await expect(askCategoryScope('t-1')).resolves.toBe('SIMILAR');
    expect(getCurrentAppAlert()).toBeUndefined();
  });

  it('offers all of them, and says which keep their category', async () => {
    similar.mockResolvedValue({ similar: 4, keptByUser: 1 });

    const answer = askCategoryScope('t-1');
    const alert = await shownAlert();
    expect(alert.message).toContain('4 other transactions from the same payee');
    expect(alert.message).toContain('1 transaction you categorised yourself will keep its category');
    expect(alert.buttons.map((b) => b.text)).toEqual(['Cancel', 'Only this one', 'All 5']);
    press('All 5');

    await expect(answer).resolves.toBe('SIMILAR');
  });

  it('returns ONLY_THIS for "Only this one"', async () => {
    similar.mockResolvedValue({ similar: 1, keptByUser: 0 });

    const answer = askCategoryScope('t-1');
    await shownAlert();
    press('Only this one');

    await expect(answer).resolves.toBe('ONLY_THIS');
  });

  it('saves nothing when cancelled, or dismissed', async () => {
    similar.mockResolvedValue({ similar: 2, keptByUser: 0 });

    const cancelled = askCategoryScope('t-1');
    await shownAlert();
    press('Cancel');
    await expect(cancelled).resolves.toBeNull();

    const dismissed = askCategoryScope('t-1');
    await shownAlert();
    dismissCurrentAppAlertByUser();
    await expect(dismissed).resolves.toBeNull();
  });

  it('sends no scope when the lookup fails, so the server keeps its old behaviour', async () => {
    similar.mockImplementation(async () => { throw new Error('network'); });

    await expect(askCategoryScope('t-1')).resolves.toBeUndefined();
    expect(getCurrentAppAlert()).toBeUndefined();
  });
});
