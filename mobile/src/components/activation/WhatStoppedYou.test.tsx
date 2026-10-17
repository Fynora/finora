import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { TextInput } from 'react-native';
import { activationApi } from '../../api/endpoints';
import { WhatStoppedYou } from './WhatStoppedYou';
import { resetActivationAnsweredForTests } from '../../lib/activationQuestions';

// Hoisted above the imports by babel-jest.
jest.mock('../../api/endpoints', () => ({
  activationApi: { answer: jest.fn(), question: jest.fn() },
}));

const answer = activationApi.answer as jest.Mock;
const question = activationApi.question as jest.Mock;

function buttonLabels(): string[] {
  return screen.getAllByRole('button').map((b) => {
    const label = b.props.accessibilityLabel;
    return typeof label === 'string' ? label : '';
  });
}

/** Lets the server check (and anything else already resolved) settle. */
async function settle() {
  await waitFor(() => expect(question).toHaveBeenCalled());
  await new Promise((r) => setTimeout(r, 0));
}

describe('WhatStoppedYou', () => {
  beforeEach(() => {
    answer.mockReset().mockResolvedValue(undefined);
    // The server allows whichever moment is asked about, unless a test says otherwise.
    question.mockReset().mockImplementation(async (m?: string) => m ?? null);
    resetActivationAnsweredForTests();
  });

  it('rendersEachMomentsExactCopy', async () => {
    const { unmount } = render(<WhatStoppedYou moment="NO_FILE" />);
    expect(await screen.findByText('Last time you looked at adding a statement. What stopped you?')).toBeOnTheScreen();
    expect(buttonLabels()).toEqual([
      "I don't have my statement", "I'm not comfortable uploading it", "I didn't know how",
      "I didn't see the point yet", 'Not now', 'Skip',
    ]);
    unmount();

    const refused = render(<WhatStoppedYou moment="REFUSED" />);
    expect(await screen.findByText('What will you do now?')).toBeOnTheScreen();
    expect(buttonLabels()).toEqual([
      'Try another file', "I don't have another statement", "I don't understand why it failed", 'Leave it for now', 'Skip',
    ]);
    refused.unmount();

    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);
    expect(await screen.findByText('What made you stop?')).toBeOnTheScreen();
    expect(buttonLabels()).toEqual([
      'The rows looked wrong', 'I uploaded the wrong file', "I'm not ready to save it", "I'll come back to it", 'Skip',
    ]);
  });

  it('postsTheTappedAnswer', async () => {
    const onDone = jest.fn();
    render(<WhatStoppedYou moment="REFUSED" onDone={onDone} />);

    fireEvent.press(await screen.findByText("I don't understand why it failed"));

    expect(answer).toHaveBeenCalledWith('REFUSED', 'DONT_UNDERSTAND_FAILURE');
    expect(await screen.findByText('Thanks.')).toBeOnTheScreen();
    expect(screen.queryByText('What will you do now?')).not.toBeOnTheScreen();
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('skipPostsSkipped', async () => {
    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);

    fireEvent.press(await screen.findByText('Skip'));

    expect(answer).toHaveBeenCalledWith('NOT_CONFIRMED', 'SKIPPED');
  });

  it('conflictHidesWithoutError', async () => {
    answer.mockRejectedValue({ response: { status: 409 } });
    const onDone = jest.fn();
    render(<WhatStoppedYou moment="NO_FILE" onDone={onDone} />);

    fireEvent.press(await screen.findByText('Not now'));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('Last time you looked at adding a statement. What stopped you?')).not.toBeOnTheScreen();
    expect(screen.queryByText('Thanks.')).not.toBeOnTheScreen();
  });

  it('sends one answer even when tapped twice quickly', async () => {
    answer.mockImplementation(() => new Promise(() => {}));
    render(<WhatStoppedYou moment="NO_FILE" />);

    fireEvent.press(await screen.findByText('Not now'));
    fireEvent.press(screen.getByText("I didn't know how"));

    expect(answer).toHaveBeenCalledTimes(1);
  });

  it('shows no further question today once one was answered', async () => {
    const first = render(<WhatStoppedYou moment="REFUSED" />);
    fireEvent.press(await screen.findByText('Try another file'));
    expect(await screen.findByText('Thanks.')).toBeOnTheScreen();
    first.unmount();

    question.mockClear();
    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);
    expect(screen.queryByText('What made you stop?')).not.toBeOnTheScreen();
    expect(question).not.toHaveBeenCalled();
  });

  it('a 409 also means today is answered, but a network failure does not', async () => {
    answer.mockRejectedValueOnce({ response: { status: 409 } });
    const first = render(<WhatStoppedYou moment="REFUSED" />);
    fireEvent.press(await screen.findByText('Skip'));
    await waitFor(() => expect(screen.queryByText('What will you do now?')).not.toBeOnTheScreen());
    first.unmount();
    const second = render(<WhatStoppedYou moment="REFUSED" />);
    expect(screen.queryByText('What will you do now?')).not.toBeOnTheScreen();
    second.unmount();

    resetActivationAnsweredForTests();
    answer.mockRejectedValueOnce(new Error('Network Error'));
    const third = render(<WhatStoppedYou moment="NO_FILE" />);
    fireEvent.press(await screen.findByText('Not now'));
    await waitFor(() => expect(screen.queryByText('Not now')).not.toBeOnTheScreen());
    third.unmount();

    render(<WhatStoppedYou moment="NO_FILE" />);
    expect(await screen.findByText('Last time you looked at adding a statement. What stopped you?')).toBeOnTheScreen();
  });

  // Spec §4.3: a moment answered or skipped is not asked again for 30 days. Only the server knows
  // that (and today's answer after a restart), so a question it would refuse is never offered.
  it('asks the server first and stays hidden when the moment may not be asked now', async () => {
    question.mockResolvedValue(null);
    render(<WhatStoppedYou moment="REFUSED" />);

    await settle();
    expect(question).toHaveBeenCalledWith('REFUSED');
    expect(screen.queryByText('What will you do now?')).not.toBeOnTheScreen();
    expect(answer).not.toHaveBeenCalled();
  });

  it('stays hidden when the server cannot be asked', async () => {
    question.mockRejectedValue(new Error('Network Error'));
    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);

    await settle();
    expect(screen.queryByText('What made you stop?')).not.toBeOnTheScreen();
  });

  it('shows nothing while the server is being asked, framed or not', () => {
    question.mockReturnValue(new Promise(() => {}));
    render(<WhatStoppedYou moment="REFUSED" framed />);

    expect(screen.queryByText('What will you do now?')).not.toBeOnTheScreen();
    expect(screen.queryByTestId('what-stopped-you-frame')).not.toBeOnTheScreen();
  });

  it('a question the server chose shows at once, without asking again', () => {
    render(<WhatStoppedYou moment="NO_FILE" chosenByServer />);

    expect(screen.getByText('Last time you looked at adding a statement. What stopped you?')).toBeOnTheScreen();
    expect(question).not.toHaveBeenCalled();
  });

  it('noTextInput', async () => {
    render(<WhatStoppedYou moment="NO_FILE" />);
    await screen.findByText('Last time you looked at adding a statement. What stopped you?');
    expect(screen.UNSAFE_queryAllByType(TextInput)).toHaveLength(0);
  });
});
