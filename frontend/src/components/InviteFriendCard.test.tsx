import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InviteFriendCard } from './InviteFriendCard';
import { invitePromptApi, referralsApi } from '../api/endpoints';
import { inviteMessage, inviteSignupLink } from '../lib/inviteLink';

vi.mock('../api/endpoints', () => ({
  invitePromptApi: { check: vi.fn(), event: vi.fn() },
  referralsApi: { myCode: vi.fn() },
}));

const check = vi.mocked(invitePromptApi.check);
const event = vi.mocked(invitePromptApi.event);
const myCode = vi.mocked(referralsApi.myCode);
const origin = window.location.origin;

function setShare(share: ((data: ShareData) => Promise<void>) | undefined) {
  Object.defineProperty(navigator, 'share', { value: share, configurable: true, writable: true });
}
function setClipboard(writeText: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true, writable: true });
}

describe('InviteFriendCard', () => {
  beforeEach(() => {
    check.mockReset().mockResolvedValue({ show: true, shownRecently: false });
    event.mockReset().mockResolvedValue(undefined);
    myCode.mockReset().mockResolvedValue({ code: 'AB12CD34' });
    setShare(undefined);
  });
  afterEach(() => setShare(undefined));

  it('asks after a first import, with the exact reward, and reports it was shown once', async () => {
    render(<InviteFriendCard statementImportIds={['imp-1']} />);

    expect(await screen.findByText('Fynora sorted your statement.')).toBeInTheDocument();
    expect(screen.getByText("Know someone who'd want this?")).toBeInTheDocument();
    expect(screen.getByText('When 7 friends you invite start paying for Plus, you earn a month of Plus free.'))
      .toBeInTheDocument();
    expect(check).toHaveBeenCalledWith(['imp-1']);
    await waitFor(() => expect(event).toHaveBeenCalledWith('SHOWN'));
    expect(event).toHaveBeenCalledTimes(1);
  });

  it('passes every import of a multi-account confirm', async () => {
    render(<InviteFriendCard statementImportIds={['imp-1', 'imp-2']} />);
    await screen.findByText('Fynora sorted your statement.');
    expect(check).toHaveBeenCalledWith(['imp-1', 'imp-2']);
  });

  it('shows nothing, and reports nothing, when the server says not now (a second import, a re-import)', async () => {
    check.mockResolvedValue({ show: false, shownRecently: true });
    const { container } = render(<InviteFriendCard statementImportIds={['imp-2']} />);
    await waitFor(() => expect(check).toHaveBeenCalled());
    await Promise.resolve();
    expect(container).toBeEmptyDOMElement();
    expect(myCode).not.toHaveBeenCalled();
    expect(event).not.toHaveBeenCalled();
  });

  it('asks nothing when the import made no statement import (nothing saved)', () => {
    const { container } = render(<InviteFriendCard statementImportIds={[]} />);
    expect(container).toBeEmptyDOMElement();
    expect(check).not.toHaveBeenCalled();
  });

  it.each([
    ['the check fails', () => check.mockRejectedValue(new Error('offline'))],
    ['the referral code cannot be read', () => myCode.mockRejectedValue(new Error('offline'))],
  ])('shows nothing when %s', async (_name, arrange) => {
    arrange();
    const { container } = render(<InviteFriendCard statementImportIds={['imp-1']} />);
    await waitFor(() => expect(check).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(container).toBeEmptyDOMElement();
    expect(event).not.toHaveBeenCalled();
  });

  it('shares the referral message through the share sheet and thanks them', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    setShare(share);
    render(<InviteFriendCard statementImportIds={['imp-1']} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Share' }));

    expect(share).toHaveBeenCalledWith({ title: 'Fynora', text: inviteMessage(origin, 'AB12CD34') });
    expect(event).toHaveBeenCalledWith('SHARE_TAPPED');
    expect(await screen.findByText('Thanks for sharing Fynora.')).toBeInTheDocument();
  });

  it('keeps the card when the share sheet is closed without sharing', async () => {
    setShare(vi.fn().mockRejectedValue(new DOMException('cancelled', 'AbortError')));
    render(<InviteFriendCard statementImportIds={['imp-1']} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Share' }));

    expect(screen.getByRole('button', { name: 'Not now' })).toBeInTheDocument();
    expect(screen.queryByText('Thanks for sharing Fynora.')).toBeNull();
  });

  it('copies the sign-up link when the browser refuses the share sheet', async () => {
    setShare(vi.fn().mockRejectedValue(new DOMException('no gesture', 'NotAllowedError')));
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard(writeText);
    render(<InviteFriendCard statementImportIds={['imp-1']} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Share' }));

    expect(writeText).toHaveBeenCalledWith(inviteSignupLink(origin, 'AB12CD34'));
    expect(await screen.findByText('Link copied. Paste it to a friend.')).toBeInTheDocument();
  });

  it('copies the sign-up link where there is no share sheet', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard(writeText);
    render(<InviteFriendCard statementImportIds={['imp-1']} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Share' }));

    expect(writeText).toHaveBeenCalledWith(inviteSignupLink(origin, 'AB12CD34'));
    expect(await screen.findByText('Link copied. Paste it to a friend.')).toBeInTheDocument();
    expect(event).toHaveBeenCalledWith('SHARE_TAPPED');
  });

  it('shows the link to copy by hand when the clipboard is unavailable too', async () => {
    setClipboard(vi.fn().mockRejectedValue(new Error('denied')));
    render(<InviteFriendCard statementImportIds={['imp-1']} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Share' }));

    expect(await screen.findByLabelText('Copy your link:')).toHaveValue(inviteSignupLink(origin, 'AB12CD34'));
  });

  it('Not now hides the card and reports it', async () => {
    const { container } = render(<InviteFriendCard statementImportIds={['imp-1']} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));

    expect(event).toHaveBeenCalledWith('DISMISSED');
    expect(container).toBeEmptyDOMElement();
  });

  it('a failed report never breaks the card', async () => {
    event.mockRejectedValue(new Error('offline'));
    render(<InviteFriendCard statementImportIds={['imp-1']} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Not now' }));
    expect(screen.queryByText('Fynora sorted your statement.')).toBeNull();
  });
});
