import { AppState, Platform, type AppStateStatus } from 'react-native';
import { rawApi } from '../api/client';
import { reportHandledEvent } from './monitoring';
import { requireOptionalNativeModule } from 'expo';
import { applyIcon, fetchWantedIcon, resetAlternateIconsModuleForTests, startFestivalIcon } from './festivalIcon';

const icons = {
  supportsAlternateIcons: true,
  getAppIconName: jest.fn<string | null, []>(),
  setAlternateAppIcon: jest.fn<Promise<string | null>, [string | null]>(),
};
jest.mock('expo', () => ({ ...jest.requireActual('expo'), requireOptionalNativeModule: jest.fn() }));
jest.mock('../api/client', () => ({ rawApi: { get: jest.fn() } }));
jest.mock('./monitoring', () => ({ reportHandledEvent: jest.fn() }));

const requireOptional = jest.mocked(requireOptionalNativeModule);
const get = jest.mocked(rawApi.get);

function answer(icon: unknown) {
  get.mockResolvedValue({ data: { success: true, data: { icon } } });
}

/** Lets every pending promise (the fetch, then the switch) settle. */
async function settle() {
  for (let i = 0; i < 5; i++) await Promise.resolve();
}

let listener: ((next: AppStateStatus) => void) | undefined;
const remove = jest.fn();

function setPlatform(os: 'ios' | 'android') {
  Object.defineProperty(Platform, 'OS', { configurable: true, get: () => os });
}

function setAppState(state: AppStateStatus) {
  Object.defineProperty(AppState, 'currentState', { configurable: true, get: () => state });
}

beforeEach(() => {
  jest.clearAllMocks();
  listener = undefined;
  jest.spyOn(AppState, 'addEventListener').mockImplementation((_type, handler) => {
    listener = handler as (next: AppStateStatus) => void;
    return { remove } as unknown as ReturnType<typeof AppState.addEventListener>;
  });
  resetAlternateIconsModuleForTests();
  requireOptional.mockReturnValue(icons);
  icons.getAppIconName.mockReturnValue(null);
  icons.setAlternateAppIcon.mockResolvedValue(null);
  icons.supportsAlternateIcons = true;
});

describe('fetchWantedIcon', () => {
  it('maps the server key to the bundled icon name', async () => {
    answer('diwali');
    await expect(fetchWantedIcon()).resolves.toBe('Diwali');
    expect(get).toHaveBeenCalledWith('/app/icon', expect.objectContaining({ timeout: 10_000 }));
  });

  it('treats "default" as the standard icon', async () => {
    answer('default');
    await expect(fetchWantedIcon()).resolves.toBeNull();
  });

  it('treats a key this build does not bundle as the standard icon, never as stuck', async () => {
    answer('holi');
    await expect(fetchWantedIcon()).resolves.toBeNull();
    answer('toString');
    await expect(fetchWantedIcon()).resolves.toBeNull();
  });

  it('says nothing (undefined) when the request fails or the answer is unreadable', async () => {
    get.mockRejectedValue(new Error('offline'));
    await expect(fetchWantedIcon()).resolves.toBeUndefined();
    get.mockResolvedValue({ data: { success: true, data: null } });
    await expect(fetchWantedIcon()).resolves.toBeUndefined();
    answer(42);
    await expect(fetchWantedIcon()).resolves.toBeUndefined();
  });
});

describe('applyIcon', () => {
  it('switches only when the icon differs', async () => {
    icons.getAppIconName.mockReturnValue('Diwali');
    await applyIcon('Diwali');
    expect(icons.setAlternateAppIcon).not.toHaveBeenCalled();

    await applyIcon(null);
    expect(icons.setAlternateAppIcon).toHaveBeenCalledWith(null);
  });

  it('does nothing on a device without alternate icons', async () => {
    icons.supportsAlternateIcons = false;
    await applyIcon('Diwali');
    expect(icons.setAlternateAppIcon).not.toHaveBeenCalled();
  });

  it('does nothing, and does not throw, in a build without the native module', async () => {
    requireOptional.mockReturnValue(null);
    await expect(applyIcon('Diwali')).resolves.toBeUndefined();
    expect(requireOptional).toHaveBeenCalledWith('ExpoAlternateAppIcons');
    expect(icons.setAlternateAppIcon).not.toHaveBeenCalled();
  });

  it('reports a failed switch instead of throwing', async () => {
    icons.setAlternateAppIcon.mockRejectedValue(new Error('denied'));
    await expect(applyIcon('Diwali')).resolves.toBeUndefined();
    expect(reportHandledEvent).toHaveBeenCalledWith('Festival app icon switch failed', 'festival_icon',
      { wanted: 'Diwali', error: 'denied' });
  });
});

describe('startFestivalIcon on Android', () => {
  beforeEach(() => {
    setPlatform('android');
    setAppState('active');
  });

  it('never switches while the app is open, only when it goes to the background', async () => {
    answer('diwali');
    startFestivalIcon();
    await settle();
    expect(icons.setAlternateAppIcon).not.toHaveBeenCalled();

    listener!('inactive');
    await settle();
    expect(icons.setAlternateAppIcon).not.toHaveBeenCalled();

    listener!('background');
    await settle();
    expect(icons.setAlternateAppIcon).toHaveBeenCalledWith('Diwali');
  });

  it('asks again on resume, and reverts on the next background when the window has ended', async () => {
    icons.getAppIconName.mockReturnValue('Diwali');
    answer('diwali');
    startFestivalIcon();
    await settle();
    listener!('background');
    await settle();
    expect(icons.setAlternateAppIcon).not.toHaveBeenCalled();

    answer('default');
    listener!('active');
    await settle();
    expect(get).toHaveBeenCalledTimes(2);
    listener!('background');
    await settle();
    expect(icons.setAlternateAppIcon).toHaveBeenCalledWith(null);
  });

  it('leaves the icon alone when the server could not be reached', async () => {
    get.mockRejectedValue(new Error('offline'));
    startFestivalIcon();
    await settle();
    listener!('background');
    await settle();
    expect(icons.setAlternateAppIcon).not.toHaveBeenCalled();
  });

  it('stops listening when unsubscribed', () => {
    answer('default');
    const stop = startFestivalIcon();
    stop();
    expect(remove).toHaveBeenCalled();
  });
});

describe('startFestivalIcon on iOS', () => {
  beforeEach(() => setPlatform('ios'));

  it('switches as soon as the answer arrives with the app open', async () => {
    setAppState('active');
    answer('diwali');
    startFestivalIcon();
    await settle();
    expect(icons.setAlternateAppIcon).toHaveBeenCalledWith('Diwali');
  });

  it('does not switch in the background (iOS refuses it) and does it on the next resume', async () => {
    setAppState('background');
    answer('diwali');
    startFestivalIcon();
    await settle();
    listener!('background');
    await settle();
    expect(icons.setAlternateAppIcon).not.toHaveBeenCalled();

    setAppState('active');
    listener!('active');
    await settle();
    expect(icons.setAlternateAppIcon).toHaveBeenCalledWith('Diwali');
  });
});
