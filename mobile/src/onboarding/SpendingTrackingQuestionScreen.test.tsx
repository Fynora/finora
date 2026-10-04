import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { SpendingTrackingQuestionScreen, SPENDING_TRACKING_OPTIONS } from './SpendingTrackingQuestionScreen';

describe('SpendingTrackingQuestionScreen', () => {
  it('lists every answer, and Continue does nothing until one is chosen', () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    render(<SpendingTrackingQuestionScreen onSubmit={onSubmit} onSignOut={jest.fn()} />);

    expect(screen.getByText('How do you keep track of your spending today?')).toBeTruthy();
    for (const opt of SPENDING_TRACKING_OPTIONS) {
      expect(screen.getByText(opt.label)).toBeTruthy();
    }
    fireEvent.press(screen.getByText('Continue'));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.queryByText(/skip/i)).toBeNull();
  });

  it('offers a way to sign out without answering, which submits nothing', () => {
    const onSubmit = jest.fn();
    const onSignOut = jest.fn();
    render(<SpendingTrackingQuestionScreen onSubmit={onSubmit} onSignOut={onSignOut} />);

    fireEvent.press(screen.getByText('Sign out'));

    expect(onSignOut).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submits the chosen answer', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    render(<SpendingTrackingQuestionScreen onSubmit={onSubmit} onSignOut={jest.fn()} />);

    fireEvent.press(screen.getByText('In a spreadsheet (Excel, Google Sheets)'));
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });

    expect(onSubmit).toHaveBeenCalledWith('SPREADSHEET');
  });

  it('shows a message and lets the user try again when saving fails', async () => {
    const onSubmit = jest.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(undefined);
    render(<SpendingTrackingQuestionScreen onSubmit={onSubmit} onSignOut={jest.fn()} />);

    fireEvent.press(screen.getByText('Roughly, in my head'));
    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });
    expect(screen.getByText(/Check your connection/)).toBeTruthy();

    await act(async () => {
      fireEvent.press(screen.getByText('Continue'));
    });
    expect(onSubmit).toHaveBeenCalledTimes(2);
  });
});
