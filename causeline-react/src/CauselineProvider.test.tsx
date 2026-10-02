// SPDX-License-Identifier: Apache-2.0
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { CauselineProvider } from './CauselineProvider.js';

describe('CauselineProvider', () => {
  it('renders its children unchanged', () => {
    const html = renderToString(
      <CauselineProvider endpoint="/causeline/api/spans">
        <button>Checkout</button>
      </CauselineProvider>,
    );

    expect(html).toBe('<button>Checkout</button>');
  });
});
