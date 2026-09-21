import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TrustStrip } from './TrustStrip';
import { trustStrip } from './landing-config';

describe('TrustStrip', () => {
  it('renders each commitment', () => {
    render(<TrustStrip />);
    trustStrip.forEach((line) => expect(screen.getByText(line)).toBeInTheDocument());
  });
});
