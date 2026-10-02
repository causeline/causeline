// SPDX-License-Identifier: Apache-2.0
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The built UI is packaged inside the causeline-spring-boot jar. It is served at /causeline
// only when Causeline is enabled, so it goes to a private classpath folder rather than a
// public static-resources location.
export default defineConfig({
  base: '/causeline/',
  plugins: [react(), tailwindcss()],
  build: {
    outDir: '../causeline-spring-boot/target/classes/causeline-ui',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/causeline/api': 'http://localhost:8080',
    },
  },
});
