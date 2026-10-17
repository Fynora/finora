import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

vi.mock('../../api/endpoints', () => ({
  activationApi: { answer: vi.fn(), question: vi.fn() },
}));

import { activationApi } from '../../api/endpoints';
import { WhatStoppedYou } from './WhatStoppedYou';
import { resetActivationAnsweredForTests } from '../../lib/activationQuestions';

const answer = vi.mocked(activationApi.answer);
const question = vi.mocked(activationApi.question);

describe('WhatStoppedYou', () => {
  beforeEach(() => {
    answer.mockReset().mockResolvedValue(undefined);
    // The server allows whichever moment is asked about, unless a test says otherwise.
    question.mockReset().mockImplementation(async (m) => m ?? null);
    resetActivationAnsweredForTests();
  });

  it('rendersEachMomentsExactCopy', async () => {
    const { unmount } = render(<WhatStoppedYou moment="NO_FILE" />);
    expect(await screen.findByText('Last time you looked at adding a statement. What stopped you?')).toBeInTheDocument();
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
      "I don't have my statement", "I'm not comfortable uploading it", "I didn't know how",
      "I didn't see the point yet", 'Not now', 'Skip',
    ]);
    unmount();

    const refused = render(<WhatStoppedYou moment="REFUSED" />);
    expect(await screen.findByText('What will you do now?')).toBeInTheDocument();
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
      'Try another file', "I don't have another statement", "I don't understand why it failed", 'Leave it for now', 'Skip',
    ]);
    refused.unmount();

    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);
    expect(await screen.findByText('What made you stop?')).toBeInTheDocument();
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual([
      'The rows looked wrong', 'I uploaded the wrong file', "I'm not ready to save it", "I'll come back to it", 'Skip',
    ]);
  });

  it('postsTheTappedAnswer', async () => {
    const onDone = vi.fn();
    render(<WhatStoppedYou moment="REFUSED" onDone={onDone} />);

    await userEvent.click(await screen.findByRole('button', { name: "I don't understand why it failed" }));

    expect(answer).toHaveBeenCalledWith('REFUSED', 'DONT_UNDERSTAND_FAILURE');
    expect(await screen.findByText('Thanks.')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('skipPostsSkipped', async () => {
    render(<WhatStoppedYou moment="NOT_CONFIRMED" />);

    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));

    expect(answer).toHaveBeenCalledWith('NOT_CONFIRMED', 'SKIPPED');
  });

  it('conflictHidesWithoutError', async () => {
    answer.mockRejectedValue({ response: { status: 409 } });
    const onDone = vi.fn();
    const { container } = render(<WhatStoppedYou moment="NO_FILE" onDone={onDone} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));

    await waitFor(() => expect(container).toBeEmptyDOMElement());
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it('sends one answer even when tapped twice quickly', async () => {
    let resolve: () => void = () => {};
    answer.mockImplementation(() => new Promise<undefined>((r) => { resolve = () => r(undefined); }));
    render(<WhatStoppedYou moment="NO_FILE" />);

    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    await userEvent.click(screen.getByRole('button', { name: "I didn't know how" }));
    resolve();

    expect(answer).toHaveBeenCalledTimes(1);
  });

  // The server takes one answer a day; a question it would refuse is not worth showing.
  it('shows no further question today once one was answered', async () => {
    const first = render(<WhatStoppedYou moment="REFUSED" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Try another file' }));
    expect(await screen.findByText('Thanks.')).toBeInTheDocument();
    first.unmount();

    question.mockClear();
    const { container } = render(<WhatStoppedYou moment="NOT_CONFIRMED" />);
    expect(container).toBeEmptyDOMElement();
    expect(question).not.toHaveBeenCalled();
  });

  it('a 409 also means today is answered, but a network failure does not', async () => {
    answer.mockRejectedValueOnce({ response: { status: 409 } });
    const first = render(<WhatStoppedYou moment="REFUSED" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Skip' }));
    await waitFor(() => expect(first.container).toBeEmptyDOMElement());
    first.unmount();
    expect(render(<WhatStoppedYou moment="REFUSED" />).container).toBeEmptyDOMElement();

    resetActivationAnsweredForTests();
    answer.mockRejectedValueOnce(new Error('Network Error'));
    const third = render(<WhatStoppedYou moment="NO_FILE" />);
    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    await waitFor(() => expect(third.container).toBeEmptyDOMElement());
    third.unmount();

    render(<WhatStoppedYou moment="NO_FILE" />);
    expect(await screen.findByText('Last time you looked at adding a statement. What stopped you?')).toBeInTheDocument();
  });

  // Spec §4.3: a moment answered or skipped is not asked again for 30 days. Only the server knows
  // that (and today's answer after a reload), so a question it would refuse is never offered.
  it('asks the server first and stays hidden when the moment may not be asked now', async () => {
    question.mockResolvedValue(null);
    const { container } = render(<WhatStoppedYou moment="REFUSED" />);

    await waitFor(() => expect(question).toHaveBeenCalledWith('REFUSED'));
    await new Promise((r) => setTimeout(r, 0));
    expect(container).toBeEmptyDOMElement();
    expect(answer).not.toHaveBeenCalled();
  });

  it('stays hidden when the server cannot be asked', async () => {
    question.mockRejectedValue(new Error('Network Error'));
    const { container } = render(<WhatStoppedYou moment="NOT_CONFIRMED" />);

    await waitFor(() => expect(question).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(container).toBeEmptyDOMElement();
  });

  it('shows nothing while the server is being asked', () => {
    question.mockReturnValue(new Promise(() => {}));
    const { container } = render(<WhatStoppedYou moment="REFUSED" />);

    expect(container).toBeEmptyDOMElement();
  });

  it('a question the server chose shows at once, without asking again', () => {
    render(<WhatStoppedYou moment="NO_FILE" chosenByServer />);

    expect(screen.getByText('Last time you looked at adding a statement. What stopped you?')).toBeInTheDocument();
    expect(question).not.toHaveBeenCalled();
  });

  it('noTextInput', async () => {
    const { container } = render(<WhatStoppedYou moment="NO_FILE" />);
    await screen.findByText('Last time you looked at adding a statement. What stopped you?');
    expect(container.querySelector('input, textarea, [contenteditable]')).toBeNull();
  });
});
