import { fireEvent, render, screen } from '@testing-library/react-native';
import { QuickActionSheet } from './QuickActionSheet';
import { ThemeProvider } from '../../theme';

describe('QuickActionSheet', () => {
  it('renders nothing when not visible', () => {
    const { queryByText } = render(
      <ThemeProvider>
        <QuickActionSheet visible={false} onClose={jest.fn()} onImportStatement={jest.fn()} onAddTransaction={jest.fn()} onAddGoal={jest.fn()} />
      </ThemeProvider>
    );
    expect(queryByText('Import Statement')).toBeNull();
  });

  it('fires the right callback per row and closes', () => {
    const onImportStatement = jest.fn();
    const onClose = jest.fn();
    render(
      <ThemeProvider>
        <QuickActionSheet visible onClose={onClose} onImportStatement={onImportStatement} onAddTransaction={jest.fn()} onAddGoal={jest.fn()} />
      </ThemeProvider>
    );

    fireEvent.press(screen.getByText('Import Statement'));
    expect(onImportStatement).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});
