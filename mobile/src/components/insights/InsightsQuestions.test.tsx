import { fireEvent, render, screen } from '@testing-library/react-native';
import { InsightsQuestions } from './InsightsQuestions';

describe('InsightsQuestions', () => {
  function show(counts = { lookAt: 2, comingUp: 3, paidRegularly: 4, withPeople: 0 }) {
    const onJump = jest.fn();
    const onOpenReports = jest.fn();
    render(<InsightsQuestions counts={counts} onJump={onJump} onOpenReports={onOpenReports} />);
    return { onJump, onOpenReports };
  }

  it('asks one question per section and jumps to it', () => {
    const { onJump } = show();
    fireEvent.press(screen.getByRole('button', { name: 'What needs a look? 2 things' }));
    fireEvent.press(screen.getByRole('button', { name: "What's coming up?" }));
    fireEvent.press(screen.getByRole('button', { name: 'What do I pay regularly?' }));
    expect(onJump.mock.calls).toEqual([['lookAt'], ['comingUp'], ['paidRegularly']]);
  });

  it('asks "Who owes me?" when someone owes, or is owed, and jumps to With people', () => {
    const { onJump } = show({ lookAt: 0, comingUp: 0, paidRegularly: 0, withPeople: 2 });
    fireEvent.press(screen.getByRole('button', { name: 'Who owes me? 2 people' }));
    expect(onJump).toHaveBeenCalledWith('withPeople');
  });

  it('leaves out "Who owes me?" with nobody to list', () => {
    show();
    expect(screen.queryByText('Who owes me?')).toBeNull();
  });

  it('sends "Where did my money go?" to Reports', () => {
    const { onOpenReports } = show();
    fireEvent.press(screen.getByRole('button', { name: 'Where did my money go? Opens Reports' }));
    expect(onOpenReports).toHaveBeenCalledTimes(1);
  });

  it('leaves out a question whose section is empty, but always offers Reports', () => {
    show({ lookAt: 0, comingUp: 0, paidRegularly: 0, withPeople: 0 });
    expect(screen.queryByText('What needs a look?')).toBeNull();
    expect(screen.queryByText("What's coming up?")).toBeNull();
    expect(screen.queryByText('What do I pay regularly?')).toBeNull();
    expect(screen.getByText('Where did my money go?')).toBeTruthy();
  });

  it('names one thing in the singular', () => {
    show({ lookAt: 1, comingUp: 0, paidRegularly: 0, withPeople: 0 });
    expect(screen.getByRole('button', { name: 'What needs a look? 1 thing' })).toBeTruthy();
  });
});
