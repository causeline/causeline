// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vite';

// Builds the SDK as one ES module that works with any bundler and in Node's ESM loader.
// Type declarations are emitted separately by tsc (see the "build" script).
export default defineConfig({
  build: {
    lib: {
      entry: 'src/index.ts',
      formats: ['es'],
      fileName: () => 'index.js',
    },
    rollupOptions: {
      // React is a peer dependency: the app's copy must be used, never a bundled one.
      external: ['react', 'react/jsx-runtime', 'react-dom'],
    },
    sourcemap: true,
    minify: false,
    emptyOutDir: true,
  },
});
