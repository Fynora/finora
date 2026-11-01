import { Linking } from 'react-native';
import * as Clipboard from 'expo-clipboard';
import { AppAlert } from './appAlert';
import { openMailto } from './openMailto';
import { reportHandledError, reportHandledEvent } from './monitoring';

/**
 * The real production error this exists for: FYNORA-MOBILE-V, an iPad Air on iOS 27.0.1 where
 * tapping the grievance address in ConsentNotice made Linking.openURL('mailto:...') reject, and
 * the bare `void Linking.openURL(...)` turned that into an unhandled promise rejection.
 */

jest.mock('./monitoring', () => ({
  reportHandledError: jest.fn(),
  reportHandledEvent: jest.fn(),
}));

jest.mock('expo-clipboard', () => ({
  setStringAsync: jest.fn().mockResolvedValue(true),
}));

const reportHandledEventMock = reportHandledEvent as jest.MockedFunction<typeof reportHandledEvent>;
const reportHandledErrorMock = reportHandledError as jest.MockedFunction<typeof reportHandledError>;
const clipboard = Clipboard as jest.Mocked<typeof Clipboard>;

const MAILTO = 'mailto:someone@example.test';
const ADDRESS = 'someone@example.test';

describe('openMailto', () => {
  beforeEach(() => {
    reportHandledEventMock.mockClear();
    reportHandledErrorMock.mockClear();
    clipboard.setStringAsync.mockClear();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('opens the mailto link it is given', async () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});

    openMailto(MAILTO, ADDRESS);
    await new Promise((resolve) => setImmediate(resolve));

    expect(openURL).toHaveBeenCalledWith(MAILTO);
    expect(alertSpy).not.toHaveBeenCalled();
    expect(reportHandledEventMock).not.toHaveBeenCalled();
  });

  it('never lets a refused link escape as an unhandled rejection, and tells the person instead', async () => {
    jest.spyOn(Linking, 'openURL').mockRejectedValue(new Error(`Unable to open URL: ${MAILTO}`));
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});

    openMailto(MAILTO, ADDRESS);
    await new Promise((resolve) => setImmediate(resolve));

    expect(alertSpy).toHaveBeenCalledTimes(1);
    const [title, message] = alertSpy.mock.calls[0];
    expect(title).toBe('Could not open your email app');
    expect(message).toContain(ADDRESS);
  });

  it('puts the address on the clipboard when the person taps Copy Address', async () => {
    jest.spyOn(Linking, 'openURL').mockRejectedValue(new Error('boom'));
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});

    openMailto(MAILTO, ADDRESS);
    await new Promise((resolve) => setImmediate(resolve));

    const copy = alertSpy.mock.calls[0][2]?.find((b) => b.text === 'Copy Address');
    expect(copy).toBeDefined();
    copy?.onPress?.();

    expect(clipboard.setStringAsync).toHaveBeenCalledWith(ADDRESS);
  });

  it('records a refusal as an info event, not an error that would keep a Sentry issue open', async () => {
    jest.spyOn(Linking, 'openURL').mockRejectedValue(new Error('boom'));
    jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});

    openMailto(MAILTO, ADDRESS);
    await new Promise((resolve) => setImmediate(resolve));

    expect(reportHandledEventMock).toHaveBeenCalledWith(expect.any(String), 'open-mailto-failed');
    expect(reportHandledErrorMock).not.toHaveBeenCalled();
  });
});
