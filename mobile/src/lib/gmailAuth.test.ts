import * as WebBrowser from 'expo-web-browser';
import { connectGmail } from './gmailAuth';
import { gmailApi } from '../api/endpoints';

jest.mock('expo-web-browser', () => ({
  openAuthSessionAsync: jest.fn(),
  WebBrowserResultType: { CANCEL: 'cancel', DISMISS: 'dismiss', OPENED: 'opened', LOCKED: 'locked' },
}));

jest.mock('../api/endpoints', () => ({
  gmailApi: { connect: jest.fn() },
}));

const mockedOpenAuthSessionAsync = WebBrowser.openAuthSessionAsync as jest.MockedFunction<
  typeof WebBrowser.openAuthSessionAsync
>;
const mockedConnect = gmailApi.connect as jest.MockedFunction<typeof gmailApi.connect>;

beforeEach(() => {
  mockedOpenAuthSessionAsync.mockReset();
  mockedConnect.mockReset();
  mockedConnect.mockResolvedValue({ authorizationUrl: 'https://accounts.google.com/o/oauth2/v2/auth?x=1' });
});

describe('connectGmail', () => {
  it('opens an auth session at the URL gmailApi.connect() returns, redirecting to the app scheme', async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: 'finora://gmail-callback?gmail=connected' });

    await connectGmail();

    expect(mockedOpenAuthSessionAsync).toHaveBeenCalledWith(
      'https://accounts.google.com/o/oauth2/v2/auth?x=1',
      'finora://gmail-callback',
    );
  });

  it("resolves 'connected' when the callback redirect carries gmail=connected", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: 'finora://gmail-callback?gmail=connected' });

    await expect(connectGmail()).resolves.toBe('connected');
  });

  it("resolves 'declined' when the callback redirect carries gmail=declined", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: 'success', url: 'finora://gmail-callback?gmail=declined' });

    await expect(connectGmail()).resolves.toBe('declined');
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
  });

  it("resolves 'cancelled' for a dismissed session too", async () => {
    mockedOpenAuthSessionAsync.mockResolvedValue({ type: WebBrowser.WebBrowserResultType.DISMISS });

    await expect(connectGmail()).resolves.toBe('cancelled');
  });
});
