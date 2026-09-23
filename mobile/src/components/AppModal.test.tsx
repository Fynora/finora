import { render, screen } from '@testing-library/react-native';
import { Text } from 'react-native';
import { AppCoveredProvider, AppModal } from './AppModal';

describe('AppModal', () => {
  it('renders like a plain Modal when the app is not covered', () => {
    render(
      <AppModal visible>
        <Text>body</Text>
      </AppModal>
    );
    expect(screen.getByText('body')).toBeTruthy();
  });

  it('treats an omitted visible prop as visible, as Modal does', () => {
    render(
      <AppModal>
        <Text>body</Text>
      </AppModal>
    );
    expect(screen.getByText('body')).toBeTruthy();
  });

  it('stays hidden when the caller says it is hidden', () => {
    render(
      <AppModal visible={false}>
        <Text>body</Text>
      </AppModal>
    );
    expect(screen.queryByText('body', { includeHiddenElements: true })).toBeNull();
  });

  it('hides itself while the app is covered, even though the caller asked for it visible', () => {
    render(
      <AppCoveredProvider value>
        <AppModal visible>
          <Text>body</Text>
        </AppModal>
      </AppCoveredProvider>
    );
    expect(screen.queryByText('body', { includeHiddenElements: true })).toBeNull();
  });
});
