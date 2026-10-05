import { Platform } from 'react-native';
import { CAMPAIGN_CHANNEL_ID, registerSystemNotificationEvents, showSystemNotification } from './systemNotification';

// The native library is replaced with a fake that records what it was asked, so these tests pin what
// systemNotification.ts sends to it and what it reports back -- including the old-build case where
// the library cannot be loaded at all.
const mockNotifee = {
  getNotificationSettings: jest.fn(),
  createChannel: jest.fn(),
  displayNotification: jest.fn(),
  onBackgroundEvent: jest.fn(),
};

jest.mock('react-native-notify-kit', () => ({
  __esModule: true,
  default: mockNotifee,
  AuthorizationStatus: { NOT_DETERMINED: -1, DENIED: 0, AUTHORIZED: 1, PROVISIONAL: 2 },
  AndroidImportance: { HIGH: 4 },
}));


const originalOS = Platform.OS;

function setPlatform(os: 'ios' | 'android') {
  Object.defineProperty(Platform, 'OS', { value: os, configurable: true });
}

beforeEach(() => {
  jest.clearAllMocks();
  jest.spyOn(console, 'warn').mockImplementation(() => {});
  mockNotifee.getNotificationSettings.mockResolvedValue({ authorizationStatus: 1 });
  mockNotifee.createChannel.mockResolvedValue(CAMPAIGN_CHANNEL_ID);
  mockNotifee.displayNotification.mockResolvedValue('shown');
  setPlatform('android');
});

afterEach(() => {
  Object.defineProperty(Platform, 'OS', { value: originalOS, configurable: true });
  jest.restoreAllMocks();
});

describe('showSystemNotification', () => {
  it('on Android, creates a HIGH-importance channel and posts the notification on it', async () => {
    const shown = await showSystemNotification('Welcome to Fynora', 'Welcome to Fynora.', 'msg-1');

    expect(shown).toBe(true);
    expect(mockNotifee.createChannel).toHaveBeenCalledWith({ id: CAMPAIGN_CHANNEL_ID, name: 'Announcements', importance: 4 });
    expect(mockNotifee.displayNotification).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'msg-1',
        title: 'Welcome to Fynora',
        body: 'Welcome to Fynora.',
        android: { channelId: CAMPAIGN_CHANNEL_ID, pressAction: { id: 'default' } },
      }),
    );
  });

  it('on iOS, asks the system to show the banner AND keep it in the notification centre', async () => {
    setPlatform('ios');

    const shown = await showSystemNotification('Welcome to Fynora', 'Welcome to Fynora.');

    expect(shown).toBe(true);
    expect(mockNotifee.createChannel).not.toHaveBeenCalled();
    expect(mockNotifee.displayNotification).toHaveBeenCalledWith(
      expect.objectContaining({
        ios: { foregroundPresentationOptions: { banner: true, list: true, sound: false, badge: false } },
        android: undefined,
      }),
    );
  });

  it('accepts provisional permission (iOS delivers quietly) as permission to show', async () => {
    mockNotifee.getNotificationSettings.mockResolvedValue({ authorizationStatus: 2 });

    expect(await showSystemNotification('T', 'B')).toBe(true);
  });

  it.each([0, -1])('shows nothing and says so when notifications are not allowed (status %i)', async (status) => {
    mockNotifee.getNotificationSettings.mockResolvedValue({ authorizationStatus: status });

    expect(await showSystemNotification('T', 'B')).toBe(false);
    expect(mockNotifee.displayNotification).not.toHaveBeenCalled();
  });

  it('reports failure instead of throwing when posting fails', async () => {
    mockNotifee.displayNotification.mockRejectedValue(new Error('boom'));

    await expect(showSystemNotification('T', 'B')).resolves.toBe(false);
  });

  it('reports failure instead of throwing when the channel cannot be created', async () => {
    mockNotifee.createChannel.mockRejectedValue(new Error('no channel'));

    await expect(showSystemNotification('T', 'B')).resolves.toBe(false);
    expect(mockNotifee.displayNotification).not.toHaveBeenCalled();
  });

  it('never puts the message text in a log line', async () => {
    mockNotifee.displayNotification.mockRejectedValue(new Error('boom'));

    await showSystemNotification('A private title', 'A private body');

    const logged = JSON.stringify((console.warn as jest.Mock).mock.calls);
    expect(logged).not.toContain('A private title');
    expect(logged).not.toContain('A private body');
  });
});

// The build with no native module is covered in systemNotification.oldBuild.test.ts (it needs a mock
// that throws, which cannot share a file with the working fake above).

describe('registerSystemNotificationEvents', () => {
  it('registers a background event handler that does nothing', async () => {
    await registerSystemNotificationEvents();

    expect(mockNotifee.onBackgroundEvent).toHaveBeenCalledTimes(1);
    const handler = mockNotifee.onBackgroundEvent.mock.calls[0][0] as () => Promise<void>;
    await expect(handler()).resolves.toBeUndefined();
  });
});
