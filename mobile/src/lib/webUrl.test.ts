import { Linking } from 'react-native';
import { AppAlert } from './appAlert';
import * as Clipboard from 'expo-clipboard';
import * as WebBrowser from 'expo-web-browser';
import { openWebUrl, webUrl } from './webUrl';
import { reportHandledError, reportHandledEvent } from './monitoring';

/**
 * Covers the real production crash openWebUrl() exists to fix: every call site that opened one of
 * these web pages used to call Linking.openURL(webUrl(path)) bare, from a bare onPress, with no
 * .catch() -- so a rejection (seen live via Sentry: a real iPhone SE on iOS 27 failing to open
 * both /trust and /your-data in the same session, mechanism: onunhandledrejection) became an
 * unhandled promise rejection reported as a crash. Also covers the second-pass fix: stopping the
 * crash isn't enough on its own -- a caller with no error UI of its own previously left the user
 * with a silent dead tap on failure, and a bare URL in an alert isn't actionable on a phone, hence
 * the Copy Link button. See this file's own doc comment on openWebUrl().
 */

jest.mock('./monitoring', () => ({
  reportHandledError: jest.fn(),
  reportHandledEvent: jest.fn(),
}));

jest.mock('expo-clipboard', () => ({
  setStringAsync: jest.fn().mockResolvedValue(true),
}));

jest.mock('expo-web-browser', () => ({
  openBrowserAsync: jest.fn(),
}));

const openBrowser = WebBrowser.openBrowserAsync as jest.MockedFunction<typeof WebBrowser.openBrowserAsync>;
const reportHandledEventMock = reportHandledEvent as jest.MockedFunction<typeof reportHandledEvent>;
const reportHandledErrorMock = reportHandledError as jest.MockedFunction<typeof reportHandledError>;
const clipboard = Clipboard as jest.Mocked<typeof Clipboard>;

describe('openWebUrl', () => {
  beforeEach(() => {
    openBrowser.mockReset();
    // Default: the in-app browser fallback ALSO fails, so the tests below that assert the alert
    // and Copy Link still describe the real end of the failure chain.
    openBrowser.mockRejectedValue(new Error('in-app browser failed too'));
    reportHandledErrorMock.mockClear();
    reportHandledEventMock.mockClear();
  });

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

  it('tells the user the link failed instead of leaving a silent dead tap', async () => {
    // The second gap: turning the crash into a handled one is not the same as the user getting
    // any feedback -- without this, tapping a failing link just does nothing visible at all.
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    jest.spyOn(Linking, 'openURL').mockRejectedValue(new Error('boom'));

    openWebUrl('/trust');
    await new Promise((resolve) => setImmediate(resolve));

    expect(alertSpy).toHaveBeenCalledTimes(1);
    const [title] = alertSpy.mock.calls[0];
    expect(title).toBe('Could not open this page');
  });

  it('puts the real URL on the clipboard when the user taps Copy Link', async () => {
    // The third gap: a bare URL printed in an alert isn't something a phone user can act on --
    // nobody retypes a URL from memory of a popup. Copy Link is what makes the failure actually
    // recoverable.
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    jest.spyOn(Linking, 'openURL').mockRejectedValue(new Error('boom'));

    openWebUrl('/trust');
    await new Promise((resolve) => setImmediate(resolve));

    const buttons = alertSpy.mock.calls[0][2];
    const copyButton = buttons?.find((b) => b.text === 'Copy Link');
    expect(copyButton).toBeDefined();

    copyButton?.onPress?.();

    expect(clipboard.setStringAsync).toHaveBeenCalledWith(webUrl('/trust'));
  });

  it('falls back to the in-app browser when the system refuses to open the link, with no alert', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    jest.spyOn(Linking, 'openURL').mockRejectedValue(new Error('Unable to open URL'));
    openBrowser.mockResolvedValue({ type: 'opened' } as never);

    openWebUrl('/privacy');
    await new Promise((resolve) => setImmediate(resolve));

    expect(openBrowser).toHaveBeenCalledWith(webUrl('/privacy'));
    expect(alertSpy).not.toHaveBeenCalled();
  });

  it('records a rescued link as an info event, not an error that would keep the issue open', async () => {
    jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    jest.spyOn(Linking, 'openURL').mockRejectedValue(new Error('Unable to open URL'));
    openBrowser.mockResolvedValue({ type: 'opened' } as never);

    openWebUrl('/privacy');
    await new Promise((resolve) => setImmediate(resolve));

    expect(reportHandledEventMock).toHaveBeenCalledWith(expect.any(String), 'open-web-url-fallback-used');
    expect(reportHandledErrorMock).not.toHaveBeenCalled();
  });

  it('does not touch the in-app browser when the normal open works', async () => {
    jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);

    openWebUrl('/terms');
    await new Promise((resolve) => setImmediate(resolve));

    expect(openBrowser).not.toHaveBeenCalled();
  });

  it('reports both failures as errors, and only then shows the alert', async () => {
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    const primary = new Error('boom');
    jest.spyOn(Linking, 'openURL').mockRejectedValue(primary);
    const fallbackError = new Error('in-app browser failed too');
    openBrowser.mockRejectedValue(fallbackError);

    openWebUrl('/trust');
    await new Promise((resolve) => setImmediate(resolve));

    expect(reportHandledErrorMock).toHaveBeenCalledWith(primary, 'open-web-url');
    expect(reportHandledErrorMock).toHaveBeenCalledWith(fallbackError, 'open-web-url-fallback');
    expect(alertSpy).toHaveBeenCalledTimes(1);
  });
});
