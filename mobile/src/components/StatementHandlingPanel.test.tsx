import { fireEvent, render, screen } from '@testing-library/react-native';
import { StatementHandlingPanel } from './StatementHandlingPanel';
import { openWebUrl } from '../lib/webUrl';

jest.mock('../lib/webUrl', () => ({ openWebUrl: jest.fn(), webUrl: (p: string) => `https://app.example.test${p}` }));

// Spec §5.2, verbatim, in this order -- the same lines as the web panel.
const LINES = [
  'Fynora reads it with its own rules. It is not sent to AI services.',
  'The original file is kept in your account, so you can download it or have it read again later.',
  'You check every row before anything is saved. If you cancel, nothing is added.',
  "If it can't be read automatically, our team may open it to fix the problem. Every opening needs a written reason, is logged, and you are told.",
  'You can delete a statement any time from Statement History. Its file is deleted from our storage 90 days later.',
  'A PDF password is saved only if you choose, and is stored encrypted.',
];

describe('StatementHandlingPanel', () => {
  it('closedByDefault', () => {
    render(<StatementHandlingPanel />);

    const toggle = screen.getByRole('button', { name: 'What happens to my statement?' });
    expect(toggle.props.accessibilityState).toMatchObject({ expanded: false });
    expect(screen.queryByText(/its own rules/)).not.toBeOnTheScreen();
  });

  it('opensAndShowsSevenItems', () => {
    render(<StatementHandlingPanel />);

    fireEvent.press(screen.getByRole('button', { name: 'What happens to my statement?' }));

    expect(screen.getByRole('button', { name: 'What happens to my statement?' }).props.accessibilityState)
      .toMatchObject({ expanded: true });
    const items = screen.getAllByTestId('statement-handling-item');
    expect(items).toHaveLength(6);
    LINES.forEach((line, i) => expect(items[i]).toHaveTextContent(line));
    // The seventh item is the link.
    expect(screen.getByRole('link', { name: 'Read the full privacy policy' })).toBeOnTheScreen();
  });

  it('linksToPrivacy', () => {
    render(<StatementHandlingPanel />);
    fireEvent.press(screen.getByRole('button', { name: 'What happens to my statement?' }));

    fireEvent.press(screen.getByRole('link', { name: 'Read the full privacy policy' }));

    expect(openWebUrl).toHaveBeenCalledWith('/privacy#uploaded-statements');
  });

  it('closes again', () => {
    render(<StatementHandlingPanel />);
    const toggle = () => screen.getByRole('button', { name: 'What happens to my statement?' });
    fireEvent.press(toggle());
    fireEvent.press(toggle());

    expect(toggle().props.accessibilityState).toMatchObject({ expanded: false });
    expect(screen.queryAllByTestId('statement-handling-item')).toHaveLength(0);
  });
});
