import React from 'react';
import ReactTestRenderer from 'react-test-renderer';

import App from '../App';

test('renders the qualified starter', async () => {
  let renderer: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(() => {
    renderer = ReactTestRenderer.create(<App />);
  });
  expect(renderer!.root.findByProps({accessibilityLabel: 'Increase count'})).toBeTruthy();
});
