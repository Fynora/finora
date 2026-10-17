import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../../api/endpoints', () => ({
  activationApi: { answer: vi.fn() },
}));

import { activationApi } from '../../api/endpoints';
import { WhatStoppedYou } from './WhatStoppedYou';
import { resetActivationAnsweredForTests } from '../../lib/activationQuestions';

const answer = vi.mocked(activationApi.answer);

describe('WhatStoppedYou', () => {
  beforeEach(() => {
    answer.mockReset().mockResolvedValue(undefined);
    resetActivationAnsweredForTests();
  });

  it('rendersEachMomentsExactCopy', () => {
    const { unmount } = render(<WhatStoppedYou moment="NO_FILE" />);
    expect(screen.getByText('Last time you looked at adding a statement. What stopped you?')).toBeInTheDocument();
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
      "I don't have my statement", "I'm not comfortable uploading it", "I didn't know how",
      "I didn't see the point yet", 'Not now', 'Skip',
    ]);
    unmount();

    const refused = render(<WhatStoppedYou moment="REFUSED" />);
    expect(screen.getByText('What will you do now?')).toBeInTheDocument();
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
      'Try another file', "I don't have another statement", "I don't understand why it failed", 'Leave it for now', 'Skip',
    ]);
    refused.unmount();

    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);
    expect(screen.getByText('What made you stop?')).toBeInTheDocument();
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
      'The rows looked wrong', 'I uploaded the wrong file', "I'm not ready to save it", "I'll come back to it", 'Skip',
    ]);
  });

  it('postsTheTappedAnswer', async () => {
    const onDone = vi.fn();
    render(<WhatStoppedYou moment="REFUSED" onDone={onDone} />);

    await userEvent.click(screen.getByRole('button', { name: "I don't understand why it failed" }));

    expect(answer).toHaveBeenCalledWith('REFUSED', 'DONT_UNDERSTAND_FAILURE');
    expect(await screen.findByText('Thanks.')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('skipPostsSkipped', async () => {
    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);

    await userEvent.click(screen.getByRole('button', { name: 'Skip' }));

    expect(answer).toHaveBeenCalledWith('NOT_CONFIRMED', 'SKIPPED');
  });

  it('conflictHidesWithoutError', async () => {
    answer.mockRejectedValue({ response: { status: 409 } });
    const onDone = vi.fn();
    const { container } = render(<WhatStoppedYou moment="NO_FILE" onDone={onDone} />);

    await userEvent.click(screen.getByRole('button', { name: 'Not now' }));

    await waitFor(() => expect(container).toBeEmptyDOMElement());
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('sends one answer even when tapped twice quickly', async () => {
    let resolve: () => void = () => {};
    answer.mockImplementation(() => new Promise<undefined>((r) => { resolve = () => r(undefined); }));
    render(<WhatStoppedYou moment="NO_FILE" />);

    await userEvent.click(screen.getByRole('button', { name: 'Not now' }));
    await userEvent.click(screen.getByRole('button', { name: "I didn't know how" }));
    resolve();

    expect(answer).toHaveBeenCalledTimes(1);
  });

  // The server takes one answer a day; a question it would refuse is not worth showing.
  it('shows no further question today once one was answered', async () => {
    const first = render(<WhatStoppedYou moment="REFUSED" />);
    await userEvent.click(screen.getByRole('button', { name: 'Try another file' }));
    expect(await screen.findByText('Thanks.')).toBeInTheDocument();
    first.unmount();

    const { container } = render(<WhatStoppedYou moment="NOT_CONFIRMED" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('a 409 also means today is answered, but a network failure does not', async () => {
    answer.mockRejectedValueOnce({ response: { status: 409 } });
    const first = render(<WhatStoppedYou moment="REFUSED" />);
    await userEvent.click(screen.getByRole('button', { name: 'Skip' }));
    await waitFor(() => expect(first.container).toBeEmptyDOMElement());
    first.unmount();
    expect(render(<WhatStoppedYou moment="REFUSED" />).container).toBeEmptyDOMElement();

    resetActivationAnsweredForTests();
    answer.mockRejectedValueOnce(new Error('Network Error'));
    const third = render(<WhatStoppedYou moment="NO_FILE" />);
    await userEvent.click(screen.getByRole('button', { name: 'Not now' }));
    await waitFor(() => expect(third.container).toBeEmptyDOMElement());
    third.unmount();

    render(<WhatStoppedYou moment="NO_FILE" />);
    expect(screen.getByText('Last time you looked at adding a statement. What stopped you?')).toBeInTheDocument();
  });

  it('noTextInput', () => {
    const { container } = render(<WhatStoppedYou moment="NO_FILE" />);
    expect(container.querySelector('input, textarea, [contenteditable]')).toBeNull();
  });
});
