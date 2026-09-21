import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { AskFyn } from './AskFyn';
import { askFyn } from './landing-config';

describe('AskFyn', () => {
  it('shows the real example prompts and the plan note', () => {
    const { container } = render(<AskFyn />);
    expect(container.querySelector('#ask-fyn')).not.toBeNull();
    askFyn.examples.forEach((e) => expect(screen.getByText(e)).toBeInTheDocument());
    expect(screen.getByText(askFyn.points[1])).toBeInTheDocument();
  });

  it('does not repeat where a question goes (owner decision 2026-09-21); the FAQ and privacy policy say it', () => {
    const { container } = render(<AskFyn />);
    expect(container.textContent).not.toMatch(/Anthropic/);
  });
});
