import { AppAlert } from '../../lib/appAlert';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AiFeaturesSection } from './AiFeaturesSection';
import { userApi } from '../../api/endpoints';
import { ThemeProvider } from '../../theme';

jest.mock('../../api/endpoints', () => ({ userApi: { get: jest.fn(), consent: jest.fn() } }));

const get = userApi.get as jest.Mock;
const consent = userApi.consent as jest.Mock;

function renderSection() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ThemeProvider><AiFeaturesSection /></ThemeProvider>
    </QueryClientProvider>
  );
}

/** The switch, once the setting has loaded (it is disabled until then). */
async function loadedSwitch() {
  const toggle = screen.getByLabelText('AI features');
  await waitFor(() => expect(toggle.props.disabled).toBeFalsy());
  return toggle;
}

beforeEach(() => {
  get.mockReset();
  consent.mockReset();
  jest.restoreAllMocks();
});

describe('AiFeaturesSection', () => {
  it('turns on without asking', async () => {
    get.mockResolvedValue({ aiProcessingConsent: false });
    consent.mockResolvedValue({ aiProcessingConsent: true });
    const alert = jest.spyOn(AppAlert, 'alert');
    renderSection();

    const toggle = await loadedSwitch();
    expect(toggle.props.value).toBe(false);
    await act(async () => { fireEvent(toggle, 'valueChange', true); });

    expect(consent).toHaveBeenCalledWith({ aiProcessing: true });
    expect(alert).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByLabelText('AI features').props.value).toBe(true));
  });

  it('shows never-asked as off', async () => {
    get.mockResolvedValue({ aiProcessingConsent: null });
    renderSection();

    expect((await loadedSwitch()).props.value).toBe(false);
  });

  /** Turning off is withdrawal: the stored AI texts are deleted, so it asks first. */
  it('asks before turning off, and saves only once confirmed', async () => {
    get.mockResolvedValue({ aiProcessingConsent: true });
    consent.mockResolvedValue({ aiProcessingConsent: false });
    const alert = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderSection();

    await act(async () => { fireEvent(await loadedSwitch(), 'valueChange', false); });

    expect(alert).toHaveBeenCalledWith('Turn off AI features?', expect.stringContaining('AI insight texts written for you so far are deleted'), expect.any(Array));
    expect(consent).not.toHaveBeenCalled();
    const buttons = alert.mock.calls[0][2] as { text: string; onPress?: () => void }[];
    await act(async () => { buttons.find((b) => b.text === 'Turn off')!.onPress!(); });

    expect(consent).toHaveBeenCalledWith({ aiProcessing: false });
  });

  it('says so when saving fails', async () => {
    get.mockResolvedValue({ aiProcessingConsent: false });
    consent.mockRejectedValue(new Error('network down'));
    renderSection();

    await act(async () => { fireEvent(await loadedSwitch(), 'valueChange', true); });

    expect(await screen.findByText(/Couldn't save that/)).toBeTruthy();
    expect(screen.getByLabelText('AI features').props.value).toBe(false);
  });

  it('keeps the switch disabled when the setting cannot be loaded', async () => {
    get.mockRejectedValue(new Error('network down'));
    renderSection();

    expect(await screen.findByText(/Couldn't load this setting/)).toBeTruthy();
    expect(screen.getByLabelText('AI features').props.disabled).toBe(true);
  });
});
