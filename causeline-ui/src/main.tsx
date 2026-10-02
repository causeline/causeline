// SPDX-License-Identifier: Apache-2.0
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { applyTheme, savedTheme } from './theme';
import './index.css';

// Before the first render, so a saved light theme never flashes dark.
applyTheme(savedTheme());

const root = document.getElementById('root');
if (!root) {
  throw new Error('Causeline UI: #root element missing');
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
