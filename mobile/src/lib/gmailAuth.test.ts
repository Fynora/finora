import * as WebBrowser from 'expo-web-browser';
import { connectGmail } from './gmailAuth';
import { gmailApi } from '../api/endpoints';

jest.mock('expo-web-browser', () => ({
  openAuthSessionAsync: jest.fn(),
  WebBrowserResultType: { CANCEL: 'cancel', DISMISS: 'dismiss', OPENED: 'opened', LOCKED: 'locked' },
}));

jest.mock('../api/endpoints', () => ({
  gmailApi: { connect: jest.fn(), confirmConnect: jest.fn() },
}));

const mockedOpenAuthSessionAsync = WebBrowser.openAuthSessionAsync as jest.MockedFunction<
  typeof WebBrowser.openAuthSessionAsync
>;
const mockedConnect = gmailApi.connect as jest.MockedFunction<typeof gmailApi.connect>;
const mockedConfirm = gmailApi.confirmConnect as jest.MockedFunction<typeof gmailApi.confirmConnect>;

const BACK_WITH_TICKET = 'finora://gmail-callback?gmail=confirm&ticket=the-ticket';

beforeEach(() => {
  mockedOpenAuthSessionAsync.mockReset();
  mockedConnect.mockReset();
  mockedConfirm.mockReset();
  mockedConnect.mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?x=1' });
  mockedConfirm.mockResolvedValue({} as never);
});

describe('connectGmail', () => {
  it('opens an auth session at the URL gmailApi.connect() returns, redirecting to the app scheme', async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: BACK_WITH_TICKET });

    await connectGmail();

    expect(mockedOpenAuthSessionAsync).toHaveBeenCalledWith(
      'https://accounts.google.com/o/oauth2/v2/auth?x=1',
      'finora://gmail-callback',
    );
  });

  // Google redirecting back connects nothing. The mailbox is connected when this signed-in app
  // posts the one-time ticket and the server accepts it.
  it("posts the ticket from the redirect and resolves 'connected' once the server accepts it", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: BACK_WITH_TICKET });

    await expect(connectGmail()).resolves.toBe('connected');

    expect(mockedConfirm).toHaveBeenCalledTimes(1);
    expect(mockedConfirm).toHaveBeenCalledWith('the-ticket');
  });

  it("resolves 'failed', not 'connected', when the server refuses the ticket", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: BACK_WITH_TICKET });
    mockedConfirm.mockRejectedValue({ response: { status: 403 } });

    await expect(connectGmail()).resolves.toBe('failed');
  });

  it("resolves 'failed' without posting when the redirect says confirm but carries no ticket", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: 'finora://gmail-callback?gmail=confirm' });

    await expect(connectGmail()).resolves.toBe('failed');
    expect(mockedConfirm).not.toHaveBeenCalled();
  });

  // No current backend sends gmail=connected, and nothing has been connected when it arrives.
  it("does not take a redirect's word for it: gmail=connected alone resolves 'failed' and posts nothing", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: 'finora://gmail-callback?gmail=connected' });

    await expect(connectGmail()).resolves.toBe('failed');
    expect(mockedConfirm).not.toHaveBeenCalled();
  });

  it('ignores a ticket on a redirect that is not a confirm', async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({
      type: 'success', url: 'finora://gmail-callback?gmail=failed&ticket=the-ticket',
    });

    await expect(connectGmail()).resolves.toBe('failed');
    expect(mockedConfirm).not.toHaveBeenCalled();
  });

  it("resolves 'declined' when the callback redirect carries gmail=declined", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: 'finora://gmail-callback?gmail=declined' });

    await expect(connectGmail()).resolves.toBe('declined');
    expect(mockedConfirm).not.toHaveBeenCalled();
  });

  it.each(['invalid', 'failed'])(
    "resolves 'failed' when the callback redirect carries gmail=%s",
    async (outcome) => {
      mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: `finora://gmail-callback?gmail=${outcome}` });

      await expect(connectGmail()).resolves.toBe('failed');
    },
  );

  it("resolves 'cancelled' when the auth session is dismissed before Google ever redirects back", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: WebBrowser.WebBrowserResultType.CANCEL });

    await expect(connectGmail()).resolves.toBe('cancelled');
    expect(mockedConfirm).not.toHaveBeenCalled();
  });

  it("resolves 'cancelled' for a dismissed session too", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: WebBrowser.WebBrowserResultType.DISMISS });

    await expect(connectGmail()).resolves.toBe('cancelled');
  });
});
