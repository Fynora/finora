import { fireEvent, render, screen } from '@testing-library/react-native';
import type { PersonBalance } from '../../api/endpoints';
import { WithPeopleSection } from './WithPeopleSection';

// Every name and amount here is invented.
const person = (personKey: string, label: string, owedToUser: number): PersonBalance => ({
  personKey, label, owedToUser, since: '2026-09-03', family: false,
});

describe('WithPeopleSection', () => {
  it('lists who owes the user and whom the user owes, each under its own heading', () => {
    render(
      <WithPeopleSection
        owedToYou={[person('vpa:friend.a', 'Friend A', 1200), person('vpa:friend.k', 'Friend K', 500)]}
        youOwe={[person('vpa:friend.p', 'Friend P', -800)]}
        onOpenPerson={jest.fn()}
      />,
    );
    expect(screen.getByText('With people')).toBeTruthy();
    expect(screen.getByText('Owed to you')).toBeTruthy();
    expect(screen.getByText('You owe')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Friend A owes you ₹1,200' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'You owe Friend P ₹800' })).toBeTruthy();
    expect(screen.getByText('₹800')).toBeTruthy();
  });

  it('opens the person page on a tap', () => {
    const onOpenPerson = jest.fn();
    const k = person('vpa:friend.k', 'Friend K', 500);
    render(<WithPeopleSection owedToYou={[k]} youOwe={[]} onOpenPerson={onOpenPerson} />);
    fireEvent.press(screen.getByRole('button', { name: 'Friend K owes you ₹500' }));
    expect(onOpenPerson).toHaveBeenCalledWith(k);
  });

  it('leaves out an empty side, and shows nothing with nobody on either', () => {
    const { rerender } = render(
      <WithPeopleSection owedToYou={[person('vpa:friend.k', 'Friend K', 500)]} youOwe={[]} onOpenPerson={jest.fn()} />,
    );
    expect(screen.queryByText('You owe')).toBeNull();
    rerender(<WithPeopleSection owedToYou={[]} youOwe={[]} onOpenPerson={jest.fn()} />);
    expect(screen.queryByText('With people')).toBeNull();
  });
});
