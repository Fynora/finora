import { fireEvent, render, screen } from '@testing-library/react-native';
import { ImportBar } from './ImportBar';

function renderBar(over: Partial<Parameters<typeof ImportBar>[0]> = {}) {
  const props = {
    message: null,
    buttonLabel: 'Import 84 transactions',
    disabled: false,
    loading: false,
    onImport: jest.fn(),
    onShowMe: jest.fn(),
    onLayoutHeight: jest.fn(),
    ...over,
  };
  render(<ImportBar {...props} />);
  return props;
}

describe('ImportBar', () => {
  it('shows the message and the button', () => {
    const p = renderBar({ message: { kind: 'blocked', text: 'Answer 1 question to import' }, disabled: true });
    expect(screen.getByText('Answer 1 question to import')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Show me' })).toBeNull();
    const button = screen.getByRole('button', { name: 'Import 84 transactions' });
    expect(button).toBeDisabled();
    fireEvent.press(button);
    expect(p.onImport).not.toHaveBeenCalled();
  });

  it('offers "Show me" for rows to check, and imports when pressed', () => {
    const p = renderBar({ message: { kind: 'check', text: '2 rows to check' } });
    fireEvent.press(screen.getByRole('button', { name: 'Show me' }));
    expect(p.onShowMe).toHaveBeenCalledTimes(1);
    fireEvent.press(screen.getByRole('button', { name: 'Import 84 transactions' }));
    expect(p.onImport).toHaveBeenCalledTimes(1);
  });

  it('shows no line when there is nothing to say', () => {
    renderBar();
    expect(screen.queryByTestId('import-bar-message')).toBeNull();
  });

  it('reports its height', () => {
    const p = renderBar();
    fireEvent(screen.getByTestId('import-bar'), 'layout', { nativeEvent: { layout: { height: 112 } } });
    expect(p.onLayoutHeight).toHaveBeenCalledWith(112);
  });
});
