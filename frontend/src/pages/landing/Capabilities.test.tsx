import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { Capabilities } from './Capabilities';
import { capabilities } from './landing-config';

describe('Capabilities', () => {
  it('renders every capability card, section id "features", and the sample-data caption', () => {
    const { container } = render(<MemoryRouter><Capabilities /></MemoryRouter>);
    expect(container.querySelector('#features')).not.toBeNull();
    // Headings, not bare text: the sample dashboard's own sidebar also says "Reports".
    capabilities.items.forEach((i) => expect(screen.getByRole('heading', { name: i.title })).toBeInTheDocument());
    expect(screen.getByText(capabilities.mockCaption)).toBeInTheDocument();
  });

  it('shows a Plus tag on exactly the two plan-gated cards', () => {
    render(<MemoryRouter><Capabilities /></MemoryRouter>);
    expect(screen.getAllByText('Plus')).toHaveLength(2);
  });
});
