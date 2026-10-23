// SPDX-License-Identifier: Apache-2.0
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { SecurityFindings } from './ReplayPanel';
import { PRESETS, SECURITY_RULES, SecurityGuide, isSecurityRule, replayVerdict } from './SecurityGuide';

const captured = {
  draft: {
    path: '/api/orders',
    query: 'page=2',
    headers: 'authorization: Bearer user-token\ncookie: SESSION=abc\nx-auth-token: t\ncontent-type: application/json',
  },
  body: '{"item":"book"}',
};

describe('security presets', () => {
  it('adds a token to the query string, keeping what was there', () => {
    expect(PRESETS['token-in-url'].apply(captured).draft.query).toBe('page=2&access_token=causeline-test');
    expect(PRESETS['token-in-url'].apply({ ...captured, draft: { ...captured.draft, query: '' } }).draft.query).toBe(
      'access_token=causeline-test',
    );
  });

  it('replaces Authorization with an unsigned token', () => {
    const headers = PRESETS['unsigned-jwt'].apply(captured).draft.headers.split('\n');

    expect(headers.filter((line) => /^authorization:/i.test(line))).toEqual([
      'Authorization: Bearer eyJhbGciOiJub25lIn0.eyJzdWIiOiJjYXVzZWxpbmUtdGVzdCJ9.',
    ]);
    expect(headers).toContain('content-type: application/json');
  });

  it('removes every credential header and nothing else', () => {
    expect(PRESETS['no-credentials'].apply(captured).draft.headers).toBe('content-type: application/json');
  });

  it('breaks the body in the same format', () => {
    expect(PRESETS['malformed-body'].apply(captured).body).toBe('{"broken": ');
    expect(PRESETS['malformed-body'].apply({ ...captured, body: 'item=book' }).body).toBe('broken=');
  });
});

describe('SecurityGuide', () => {
  it('explains each check and offers to fill in the ones a replay can try', () => {
    const html = renderToString(<SecurityGuide open={false} onClose={() => {}} onTry={() => {}} />);

    expect(html).toContain('Security checks');
    expect(html).toContain('Secret in URL');
    expect(html).toContain('Works without credentials?');
    expect(html).toContain('not a security scan');
    expect(html.match(/<button[^>]*>Fill in</g)).toHaveLength(4);
  });

  it('walks through the checks, pointing to the replay panel where nothing can be replayed', () => {
    const here = renderToString(<SecurityGuide open={false} onClose={() => {}} onTry={() => {}} />);
    expect(here).toContain('How to use the security checks');
    expect(here).toContain('Set up probe');
    expect(here).toContain('hasNoSecurityFindings()');
    const elsewhere = renderToString(<SecurityGuide open={false} onClose={() => {}} />);
    expect(elsewhere).toContain('How to use the security checks');
    expect(elsewhere).toContain('in the Replay panel');
  });

  it('only explains where nothing can be replayed, and skips the body case without a body', () => {
    expect(renderToString(<SecurityGuide open={false} onClose={() => {}} />)).not.toContain('Fill in');
    const noBody = renderToString(<SecurityGuide open={false} onClose={() => {}} onTry={() => {}} canEditBody={false} />);
    expect(noBody.match(/<button[^>]*>Fill in</g)).toHaveLength(3);
  });

  it('lists the active probes, which need your input, apart from the ready-made cases', () => {
    const html = renderToString(<SecurityGuide open={false} onClose={() => {}} onTry={() => {}} />);

    expect(html).toContain('Another ID (IDOR)');
    expect(html).toContain('Extra field (mass assignment)');
    expect(html).toContain('Redirect elsewhere (open redirect)');
    expect(html).toContain('Without a header (auth boundary)');
    expect(html).toContain('never a scan');
    expect(html.match(/<button[^>]*>Set up probe</g)).toHaveLength(4);
    // Mass assignment adds a field to a body, so it is not offered for a request without one.
    const noBody = renderToString(<SecurityGuide open={false} onClose={() => {}} onTry={() => {}} canEditBody={false} />);
    expect(noBody.match(/<button[^>]*>Set up probe</g)).toHaveLength(3);
    expect(renderToString(<SecurityGuide open={false} onClose={() => {}} />)).not.toContain('Set up probe');
  });

  it('files server-side request forgery under broken access control, as OWASP 2025 does', () => {
    expect(SECURITY_RULES.REQUEST_VALUE_IN_OUTBOUND_URL).toBe('A01');
    expect(SECURITY_RULES.ERROR_DETAILS_EXPOSED).toBe('A10');
  });

  it('knows which insight rules are security checks, new ones included', () => {
    expect(isSecurityRule('WEAK_JWT')).toBe(true);
    expect(isSecurityRule('SECRET_IN_LOG')).toBe(true);
    expect(isSecurityRule('REQUEST_VALUE_IN_OUTBOUND_URL')).toBe(true);
    expect(isSecurityRule('PRIMARY_BOTTLENECK')).toBe(false);
  });

  it('shows the across-all-traces overview with dismiss when given one', () => {
    const html = renderToString(
      <SecurityGuide
        open={false}
        onClose={() => {}}
        token="t"
        // The overview loads on open via fetch; here we only check the static copy is present.
      />,
    );

    expect(html).toContain('What it checks');
    // Each check now carries why and fix advice.
    expect(html).toContain('Why:');
    expect(html).toContain('Fix:');
  });
});

describe('replayVerdict', () => {
  it('calls out an endpoint that answers without credentials', () => {
    expect(replayVerdict('no-credentials', 200)).toEqual({
      tone: 'bad',
      text: expect.stringContaining('without credentials'),
    });
    expect(replayVerdict('no-credentials', 401)).toEqual({ tone: 'good', text: expect.stringContaining('requires authentication') });
  });

  it('calls out an app that accepts an unsigned token', () => {
    expect(replayVerdict('unsigned-jwt', 201)?.tone).toBe('bad');
    expect(replayVerdict('unsigned-jwt', 403)?.tone).toBe('good');
  });

  it('has no verdict for cases where the status is not the signal', () => {
    expect(replayVerdict('token-in-url', 200)).toBeUndefined();
    expect(replayVerdict('malformed-body', 400)).toBeUndefined();
    expect(replayVerdict('no-credentials', 500)).toBeUndefined();
  });
});

describe('SecurityFindings', () => {
  it('lists what the checks found on the replay with its OWASP category', () => {
    const html = renderToString(
      <SecurityFindings
        findings={[{ rule: 'SECRET_IN_URL', spanId: 'a', label: 'Secret in URL: access_token', sharePercent: 0 }]}
        onOpen={() => {}}
      />,
    );

    expect(html).toContain('A04');
    expect(html).toContain('Secret in URL: access_token');
    expect(html).toContain('Open the replay');
  });

  it('says when nothing was found, without promising safety', () => {
    expect(renderToString(<SecurityFindings findings={[]} />)).toContain('a hint, not a guarantee');
  });
});
