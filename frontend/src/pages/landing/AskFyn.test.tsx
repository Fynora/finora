import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AskFyn } from './AskFyn';
import { askFyn } from './landing-config';

describe('AskFyn', () => {
  it('shows the real example prompts, the plan note, and the Anthropic disclosure', () => {
    const { container } = render(<AskFyn />);
    expect(container.querySelector('#ask-fyn')).not.toBeNull();
    askFyn.examples.forEach((e) => expect(screen.getByText(e)).toBeInTheDocument());
    expect(screen.getByText(askFyn.disclosure)).toBeInTheDocument();
    expect(screen.getByText(askFyn.points[1])).toBeInTheDocument();
  });
});
