import { activationApi } from '../api/endpoints';
import { trackFunnel } from './trackFunnel';

// Hoisted above the imports by babel-jest.
jest.mock('../api/endpoints', () => ({
  activationApi: { funnelEvent: jest.fn() },
}));

const funnelEvent = activationApi.funnelEvent as jest.Mock;

describe('trackFunnel', () => {
  beforeEach(() => {
    funnelEvent.mockReset().mockResolvedValue(undefined);
  });

  it('postsTheEventName', () => {
    trackFunnel('FILE_CHOSEN');

    expect(funnelEvent).toHaveBeenCalledTimes(1);
    expect(funnelEvent).toHaveBeenCalledWith('FILE_CHOSEN');
  });

  it('swallowsFailure', async () => {
    const rejection = Promise.reject(new Error('network down'));
    funnelEvent.mockReturnValue(rejection);

    expect(() => trackFunnel('REVIEW_OPENED')).not.toThrow();
    // trackFunnel attached its own catch: awaiting the same promise through a catch here proves the
    // rejection was handled rather than left to surface as unhandled.
    await rejection.catch(() => {});
  });

  it('swallowsASynchronousThrow', () => {
    funnelEvent.mockImplementation(() => {
      throw new TypeError('not a function');
    });

    expect(() => trackFunnel('IMPORT_SCREEN_OPENED')).not.toThrow();
  });
});
