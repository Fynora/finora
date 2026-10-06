import { registerSystemNotificationEvents, showSystemNotification } from './systemNotification';

// An over-the-air update reaches builds made before react-native-notify-kit existed, and loading a
// missing native module throws. That must read as "not shown" -- so the in-app banner takes over and
// the message is not lost -- and must never crash the app or the push handler.
jest.mock('react-native-notify-kit', () => {
  throw new Error('NotifyKit native module not found');
});


beforeEach(() => {
  jest.spyOn(console, 'warn').mockImplementation(() => {});
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('in a build without the native module', () => {
  it('reports "not shown" instead of throwing', async () => {
    await expect(showSystemNotification('Welcome to Fynora', 'Welcome to Fynora.')).resolves.toBe(false);
  });

  it('registers nothing and does not throw at start-up', async () => {
    await expect(registerSystemNotificationEvents()).resolves.toBeUndefined();
  });

  it('logs the reason without the message text', async () => {
    await showSystemNotification('A private title', 'A private body');

    const logged = JSON.stringify((console.warn as jest.Mock).mock.calls);
    expect(logged).toContain('not available in this build');
    expect(logged).not.toContain('A private title');
    expect(logged).not.toContain('A private body');
  });
});
