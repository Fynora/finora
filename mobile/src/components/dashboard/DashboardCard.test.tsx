import { render, screen } from '@testing-library/react-native';
import { StyleSheet, Text } from 'react-native';
import { DashboardCard } from './DashboardCard';

describe('DashboardCard', () => {
  it('renders its children', () => {
    render(<DashboardCard><Text>hello</Text></DashboardCard>);
    expect(screen.getByText('hello')).toBeTruthy();
  });

  it("uses a hairline border, not Card's full 1px border (subtle-not-zero, per correction)", () => {
    render(<DashboardCard testID="card"><Text>x</Text></DashboardCard>);
    const flatStyle = Array.isArray(screen.getByTestId('card').props.style)
      ? Object.assign({}, ...screen.getByTestId('card').props.style)
      : screen.getByTestId('card').props.style;
    expect(flatStyle.borderWidth).toBe(StyleSheet.hairlineWidth);
  });
});
