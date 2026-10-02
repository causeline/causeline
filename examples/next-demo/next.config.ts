// SPDX-License-Identifier: Apache-2.0
import type { NextConfig } from 'next';

const spring = process.env.SPRING_URL ?? 'http://localhost:8080';

const config: NextConfig = {
  // The browser SDK sends its spans to /causeline/api/spans; the Spring app serves Causeline.
  async rewrites() {
    return [{ source: '/causeline/:path*', destination: `${spring}/causeline/:path*` }];
  },
};

export default config;
