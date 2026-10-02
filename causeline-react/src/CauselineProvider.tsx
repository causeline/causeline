// SPDX-License-Identifier: Apache-2.0
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { install } from './runtime.js';
import type { CaptureOptions } from './runtime.js';

// Bundlers replace process.env.NODE_ENV at build time; without a bundler it does not exist.
declare const process: { env: { NODE_ENV?: string } };

function isProductionBuild(): boolean {
  try {
    return process.env.NODE_ENV === 'production';
  } catch {
    return false;
  }
}

export interface CauselineProviderProps {
  /** Where frontend spans are sent, e.g. `/causeline/api/spans`. Causeline stays off without it. */
  endpoint?: string;
  /** Origins that receive the `traceparent` header. Defaults to the page's own origin. */
  propagateTo?: string[];
  /** Label requests that follow a click with that click as an inferred cause. Off by default. */
  captureClicks?: boolean;
  /** Requests to leave untraced, e.g. `['/api/health', /analytics/]`. Read once, when the provider mounts. */
  ignore?: Array<string | RegExp>;
  /** What to record; everything by default. `{ query: false, stateValues: false }` records the least. */
  capture?: CaptureOptions;
  children?: ReactNode;
}

/**
 * Root of the Causeline SDK. It never changes what renders. In development it captures network
 * calls and traced actions; in production builds, or without an endpoint, it does nothing.
 */
export function CauselineProvider({ endpoint, propagateTo, captureClicks, ignore, capture, children }: CauselineProviderProps) {
  const origins = propagateTo?.join(' ');
  // Inline arrays are new on every render; keep the first so the SDK is not reinstalled each time.
  const [ignoreRules] = useState(ignore);
  const captureQuery = capture?.query;
  const captureStateValues = capture?.stateValues;
  const captureBodies = capture?.bodies;
  useEffect(() => {
    if (!endpoint || isProductionBuild()) {
      return undefined;
    }
    const installation = install({
      endpoint,
      propagateTo: origins ? origins.split(' ') : undefined,
      captureClicks,
      ignore: ignoreRules,
      capture: { query: captureQuery, stateValues: captureStateValues, bodies: captureBodies },
    });
    return installation.uninstall;
  }, [endpoint, origins, captureClicks, ignoreRules, captureQuery, captureStateValues, captureBodies]);

  return <>{children}</>;
}
