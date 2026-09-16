import { Linking } from 'react-native';
import { openWebUrl, webUrl } from './webUrl';
import { reportHandledError } from './monitoring';

/**
 * Covers the real production crash openWebUrl() exists to fix: every call site that opened one of
 * these web pages used to call Linking.openURL(webUrl(path)) bare, from a bare onPress, with no
 * .catch() -- so a rejection (seen live via Sentry: a real iPhone SE on iOS 27 failing to open
 * both /trust and /your-data in the same session, mechanism: onunhandledrejection) became an
 * unhandled promise rejection reported as a crash. See this file's own doc comment on
 * openWebUrl().
 */

jest.mock('./monitoring', () => ({
  reportHandledError: jest.fn(),
}));

const reportHandledErrorMock = reportHandledError as jest.MockedFunction<typeof reportHandledError>;

describe('openWebUrl', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('opens the correct absolute URL for the given path', () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);

    openWebUrl('/trust');

    expect(openURL).toHaveBeenCalledWith(webUrl('/trust'));
  });

  it('never lets a rejected openURL() escape as an unhandled promise rejection', async () => {
    // The actual regression: without openWebUrl's own .catch(), this rejection would be unhandled
    // and Jest's own unhandled-rejection detection (like Sentry's onunhandledrejection handler in
    // production) would flag it. Awaiting a microtask tick here is what would surface that.
    const rejection = new Error('Unable to open URL: https://app.fynora.net/trust');
    jest.spyOn(Linking, 'openURL').mockRejectedValue(rejection);

    openWebUrl('/trust');
    await new Promise((resolve) => setImmediate(resolve));

    expect(reportHandledErrorMock).toHaveBeenCalledWith(rejection, 'open-web-url');
  });
});
