import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { TextInput } from 'react-native';
import { activationApi } from '../../api/endpoints';
import { WhatStoppedYou } from './WhatStoppedYou';

// Hoisted above the imports by babel-jest.
jest.mock('../../api/endpoints', () => ({
  activationApi: { answer: jest.fn() },
}));

const answer = activationApi.answer as jest.Mock;

function buttonLabels(): string[] {
  return screen.getAllByRole('button').map((b) => {
    const label = b.props.accessibilityLabel;
    return typeof label === 'string' ? label : '';
  });
}

describe('WhatStoppedYou', () => {
  beforeEach(() => {
    answer.mockReset().mockResolvedValue(undefined);
  });

  it('rendersEachMomentsExactCopy', () => {
    const { unmount } = render(<WhatStoppedYou moment="NO_FILE" />);
    expect(screen.getByText('Last time you looked at adding a statement. What stopped you?')).toBeOnTheScreen();
    expect(buttonLabels()).toEqual([
      "I don't have my statement", "I'm not comfortable uploading it", "I didn't know how",
      "I didn't see the point yet", 'Not now', 'Skip',
    ]);
    unmount();

    const refused = render(<WhatStoppedYou moment="REFUSED" />);
    expect(screen.getByText('What will you do now?')).toBeOnTheScreen();
    expect(buttonLabels()).toEqual([
      'Try another file', "I don't have another statement", "I don't understand why it failed", 'Leave it for now', 'Skip',
    ]);
    refused.unmount();

    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);
    expect(screen.getByText('What made you stop?')).toBeOnTheScreen();
    expect(buttonLabels()).toEqual([
      'The rows looked wrong', 'I uploaded the wrong file', "I'm not ready to save it", "I'll come back to it", 'Skip',
    ]);
  });

  it('postsTheTappedAnswer', async () => {
    const onDone = jest.fn();
    render(<WhatStoppedYou moment="REFUSED" onDone={onDone} />);

    fireEvent.press(screen.getByText("I don't understand why it failed"));

    expect(answer).toHaveBeenCalledWith('REFUSED', 'DONT_UNDERSTAND_FAILURE');
    expect(await screen.findByText('Thanks.')).toBeOnTheScreen();
    expect(screen.queryByText('What will you do now?')).not.toBeOnTheScreen();
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('skipPostsSkipped', () => {
    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);

    fireEvent.press(screen.getByText('Skip'));

    expect(answer).toHaveBeenCalledWith('NOT_CONFIRMED', 'SKIPPED');
  });

  it('conflictHidesWithoutError', async () => {
    answer.mockRejectedValue({ response: { status: 409 } });
    const onDone = jest.fn();
    render(<WhatStoppedYou moment="NO_FILE" onDone={onDone} />);

    fireEvent.press(screen.getByText('Not now'));

    await waitFor(() => expect(onDone).toHaveBeenCalledTimes(1));
    expect(screen.queryByText('Last time you looked at adding a statement. What stopped you?')).not.toBeOnTheScreen();
    expect(screen.queryByText('Thanks.')).not.toBeOnTheScreen();
  });

  it('sends one answer even when tapped twice quickly', () => {
    answer.mockImplementation(() => new Promise(() => {}));
    render(<WhatStoppedYou moment="NO_FILE" />);

    fireEvent.press(screen.getByText('Not now'));
    fireEvent.press(screen.getByText("I didn't know how"));

    expect(answer).toHaveBeenCalledTimes(1);
  });

  it('noTextInput', () => {
    render(<WhatStoppedYou moment="NO_FILE" />);
    expect(screen.UNSAFE_queryAllByType(TextInput)).toHaveLength(0);
  });
});
