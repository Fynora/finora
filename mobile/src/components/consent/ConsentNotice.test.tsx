import { Linking } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { ConsentNotice } from './ConsentNotice';
import { GRIEVANCE_EMAIL, GRIEVANCE_MAILTO } from '../../lib/contact';
import { AppAlert } from '../../lib/appAlert';

jest.mock('../../lib/monitoring', () => ({
  reportHandledError: jest.fn(),
  reportHandledEvent: jest.fn(),
}));
jest.mock('expo-clipboard', () => ({ setStringAsync: jest.fn().mockResolvedValue(true) }));

describe('ConsentNotice', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('opens the grievance mail link when the address is tapped', () => {
    const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);

    render(<ConsentNotice />);
    fireEvent.press(screen.getByText(GRIEVANCE_EMAIL));

    expect(openURL).toHaveBeenCalledWith(GRIEVANCE_MAILTO);
  });

  // FYNORA-MOBILE-V: on an iPad with no mail account, openURL rejected and the bare call turned it
  // into an unhandled promise rejection with nothing shown to the person.
  it('tells the person when the device cannot open mail, instead of failing silently', async () => {
    jest.spyOn(Linking, 'openURL').mockRejectedValue(new Error(`Unable to open URL: ${GRIEVANCE_MAILTO}`));
    const alertSpy = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});

    render(<ConsentNotice />);
    fireEvent.press(screen.getByText(GRIEVANCE_EMAIL));
    await new Promise((resolve) => setImmediate(resolve));

    expect(alertSpy).toHaveBeenCalledTimes(1);
    expect(alertSpy.mock.calls[0][0]).toBe('Could not open your email app');
    expect(alertSpy.mock.calls[0][1]).toContain(GRIEVANCE_EMAIL);
  });
});
