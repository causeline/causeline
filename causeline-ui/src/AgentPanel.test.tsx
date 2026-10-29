// SPDX-License-Identifier: Apache-2.0
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import {
  AgentButton,
  AgentContent,
  agentState,
  claudeCommand,
  clientConfig,
  firstSentence,
  replayRules,
} from './AgentPanel';
import type { McpInfo, Onboarding, TraceSummary } from './api';
import { TraceList } from './TraceList';

describe('tool summaries', () => {
  it('stop at the first sentence, not at "e.g."', () => {
    expect(firstSentence('Sends a captured request again, e.g. to check a fix, and returns it. More.')).toBe(
      'Sends a captured request again, e.g. to check a fix, and returns it.',
    );
    expect(firstSentence('No full stop')).toBe('No full stop');
  });
});

const info = (partial: Partial<McpInfo> = {}): McpInfo => ({
  path: '/causeline/mcp',
  hide: [],
  allow: [],
  deny: [],
  safeMethodsOnly: true,
  maxReplaysPerMinute: 20,
  targets: [
    { name: 'local', allowed: true },
    { name: 'qa', allowed: false },
  ],
  replayAvailable: true,
  tools: [
    { name: 'list_traces', description: 'Recent traces. More detail.', readOnly: true },
    { name: 'replay_request', description: 'Sends a captured request again. More.', readOnly: false },
  ],
  ...partial,
});

const onboarding = (partial: Partial<Onboarding> = {}): Onboarding => ({
  appName: 'checkout-demo',
  serverSpans: 1,
  browserSpans: 0,
  namedActions: 0,
  lastServerSpanAt: null,
  lastBrowserSpanAt: null,
  ...partial,
});

const trace = (partial: Partial<TraceSummary>): TraceSummary => ({
  traceId: 'abc'.padEnd(32, '0'),
  name: 'POST /api/orders',
  status: 'OK',
  startTimeUnixNano: 0,
  durationNanos: 300,
  spanCount: 3,
  replayOf: null,
  imported: false,
  ...partial,
});

const render = (element: React.ReactElement) => renderToString(element).replaceAll('<!-- -->', '');

describe('connect commands', () => {
  it('match the command the application log prints', () => {
    expect(claudeCommand('http://localhost:8080', '/causeline/mcp', 'tok')).toBe(
      'claude mcp add --transport http causeline http://localhost:8080/causeline/mcp --header "X-Causeline-Token: tok"',
    );
  });

  it('give other clients a JSON server entry', () => {
    expect(JSON.parse(clientConfig('http://localhost:8080', '/causeline/mcp', 'tok'))).toEqual({
      mcpServers: { causeline: { url: 'http://localhost:8080/causeline/mcp', headers: { 'X-Causeline-Token': 'tok' } } },
    });
  });

  it('say where to find the token when the UI has none', () => {
    expect(claudeCommand('http://localhost:8080', '/causeline/mcp', undefined)).toContain('<token from the Causeline UI');
  });
});

describe('replay rules in plain words', () => {
  it('explain the GET-and-HEAD default and the allowed targets', () => {
    expect(replayRules(info())).toEqual([
      'Only GET and HEAD requests. List anything else in causeline.mcp.replay.allow.',
      'To: local. Other targets need allow-mcp: true.',
      'At most 20 replays a minute. Requests with side effects need its confirmation.',
    ]);
  });

  it('list what the developer allowed and denied', () => {
    const rules = replayRules(info({ safeMethodsOnly: false, allow: ['POST /api/orders'], deny: ['DELETE /**'] }));
    expect(rules[0]).toBe('Only: POST /api/orders');
    expect(rules[1]).toBe('Never: DELETE /**');
  });

  it('say when replay is unavailable', () => {
    expect(replayRules(info({ replayAvailable: false }))).toEqual(['Replay is not available in this application.']);
  });
});

describe('agent status', () => {
  it('is connected once an agent has called a tool', () => {
    expect(agentState(onboarding())).toEqual({ connected: false, label: 'No agent has called yet' });
    expect(agentState(onboarding({ agentCalls: 3 })).connected).toBe(true);
    expect(agentState(onboarding({ agentCalls: 1 })).label).toBe('1 tool call');
  });

  it('shows on the header button', () => {
    expect(render(<AgentButton onboarding={onboarding()} onClick={() => {}} />)).toContain('Connect agent');
    expect(render(<AgentButton onboarding={onboarding({ agentCalls: 2 })} onClick={() => {}} />)).toContain('Agent connected');
  });
});

describe('agent panel', () => {
  const panel = (props: Partial<Parameters<typeof AgentContent>[0]>) =>
    render(
      <AgentContent
        info={info()}
        token="secret-token-value"
        onboarding={onboarding()}
        traces={[]}
        onClose={() => {}}
        onOpenTrace={() => {}}
        {...props}
      />,
    );

  it('explains what the agent can do, sees and may replay', () => {
    const html = panel({ info: info({ hide: ['/api/admin/**'] }) });

    expect(html).toContain('Your coding agent can use Causeline');
    expect(html).toContain('claude mcp add --transport http causeline');
    expect(html).toContain('redacted');
    expect(html).toContain('/api/admin/**');
    expect(html).toContain('Only GET and HEAD requests');
    expect(html).toContain('replay_request');
    expect(html).toContain('sends requests');
    expect(html).toContain('change headers');
  });

  it('masks the token on screen', () => {
    expect(panel({})).not.toContain('secret-token-value');
  });

  it('lists the replays the agent sent', () => {
    const html = panel({ traces: [trace({ byAgent: true, replayOf: 'x', name: 'POST /api/checkout' })] });
    expect(html).toContain('Replayed by your agent');
    expect(html).toContain('POST /api/checkout');
  });

  it('says when agent access is switched off', () => {
    const html = panel({ info: null });
    expect(html).toContain('causeline.mcp.enabled=false');
    expect(html).not.toContain('claude mcp add');
  });
});

describe('trace list', () => {
  it('labels replays sent by the agent', () => {
    const html = render(
      <TraceList traces={[trace({ byAgent: true, replayOf: 'x' }), trace({ traceId: 'def'.padEnd(32, '0') })]} selected={undefined} onSelect={() => {}} />,
    );
    expect(html.match(/>Agent</g)).toHaveLength(1);
  });
});
