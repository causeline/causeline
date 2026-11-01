# SPDX-License-Identifier: Apache-2.0
import re
from pathlib import Path

import pytest

from causeline._privacy import AI_TEXT, REDACTED, WITHHELD, Outbound, _BUILT_IN_KEYS, is_loopback
from causeline._runtime import resolve_settings

REPOSITORY = Path(__file__).resolve().parents[2]
JAVA = REPOSITORY / "causeline-spring-boot/src/main/java/dev/causeline/spring/internal"
SECRET = "sk-seeded-secret"
LOCAL = Outbound(local=True)
REMOTE = Outbound(local=False)


def resolve(**options):
    environ = options.pop("environ", {})
    return resolve_settings(endpoint="http://localhost:8080", token="t", environ=environ, **options)


def test_ai_text_names_match_the_ones_spring_withholds():
    source = (JAVA / "ai/AiAttributes.java").read_text(encoding="utf-8")
    names = dict(re.findall(r'String (\w+) = "([a-z_.]+)"', source))
    listed = re.search(r"Set<String> TEXT = Set\.of\(([^)]*)\)", source).group(1)
    assert {names[name] for name in re.findall(r"\w+", listed)} == AI_TEXT


def test_withheld_text_matches_springs():
    source = (JAVA / "ai/AiAttributes.java").read_text(encoding="utf-8")
    assert f'WITHHELD = "{WITHHELD}"' in source


def test_sensitive_keys_match_springs():
    source = (JAVA / "capture/SensitiveData.java").read_text(encoding="utf-8")
    assert f'"{_BUILT_IN_KEYS}"' in source


@pytest.mark.parametrize("url", [
    "http://localhost:8080",
    "http://LOCALHOST:8080",
    "http://127.0.0.1:8080",
    "http://127.5.5.5",
    "http://[::1]:8080",
    "http://app.localhost",
])
def test_loopback_endpoints(url):
    assert is_loopback(url)


@pytest.mark.parametrize("url", [
    "http://qa.example.com",
    "http://localhost.example.com",
    "http://192.168.1.5:8080",
    "http://0.0.0.0:8080",
    "http://example.com/127.0.0.1",
])
def test_other_endpoints_are_not_loopback(url):
    assert not is_loopback(url)


def test_ai_text_reaches_a_local_causeline():
    attributes = {name: SECRET for name in AI_TEXT}
    assert LOCAL.attributes(attributes) == attributes


def test_ai_text_is_withheld_from_any_other_host():
    out = REMOTE.attributes({name: SECRET for name in AI_TEXT})
    assert set(out.values()) == {WITHHELD}
    assert SECRET not in str(out)


def test_send_ai_text_allows_another_host():
    allowed = Outbound(local=False, send_ai_text=True)
    assert allowed.attributes({"gen_ai.input.messages": SECRET}) == {"gen_ai.input.messages": SECRET}


def test_metadata_about_ai_calls_stays_on_every_host():
    attributes = {"causeline.ai.documents": '[{"id":"a"}]', "gen_ai.usage.input_tokens": "5",
                  "causeline.ai.input.chars": "9"}
    assert REMOTE.attributes(attributes) == attributes


def test_sending_does_not_change_the_recorded_span():
    attributes = {"gen_ai.input.messages": SECRET}
    REMOTE.attributes(attributes)
    assert attributes == {"gen_ai.input.messages": SECRET}


@pytest.mark.parametrize("outbound", [LOCAL, REMOTE])
def test_sensitive_query_values_never_leave(outbound):
    out = outbound.attributes({"url.query": f"page=2&api_key={SECRET}&Password={SECRET}&flag"})
    assert out["url.query"] == f"page=2&api_key={REDACTED}&Password={REDACTED}&flag"


def test_encoded_query_keys_are_still_recognised():
    assert SECRET not in LOCAL.redact_query(f"api%5Fkey={SECRET}")


def test_redact_keys_hide_more_query_values():
    outbound = Outbound(local=True, redact_keys=("internal_id",))
    assert outbound.redact_query("internal_id=7&page=2") == f"internal_id={REDACTED}&page=2"


def test_redact_keys_are_text_not_patterns():
    outbound = Outbound(local=True, redact_keys=("a.c",))
    assert outbound.redact_query("abc=1") == "abc=1"


def test_error_text_reaches_a_local_causeline_whole():
    attributes = {"exception.message": f"password={SECRET}"}
    assert LOCAL.attributes(attributes) == attributes


@pytest.mark.parametrize("key", ["exception.message", "exception.stacktrace"])
def test_error_text_is_scrubbed_for_any_other_host(key):
    out = REMOTE.attributes({key: f"failed with password={SECRET}"})
    assert SECRET not in out[key]
    assert out[key].startswith("failed with password=")


@pytest.mark.parametrize("text", [
    f"password={SECRET}",
    f"password: {SECRET}",
    f'{{"token": "{SECRET}"}}',
    f"Authorization: Bearer {SECRET}",
    f"user: password={SECRET}",
    f"Basic {SECRET}",
])
def test_scrubbing_hides_credentials(text):
    assert SECRET not in REMOTE.scrub_text(text)


def test_scrubbing_leaves_ordinary_text():
    text = "order 12 failed: name=Ann status: closed"
    assert REMOTE.scrub_text(text) == text


def test_a_loopback_endpoint_counts_as_local():
    assert resolve().local
    assert not resolve_settings(endpoint="http://qa.example.com", token="t", environ={}).local


def test_ai_text_is_captured_by_default():
    assert resolve().capture == {"prompts", "completions", "documents"}


def test_capture_switches_can_be_turned_off():
    assert resolve(capture=["prompts"]).capture == {"prompts"}
    assert resolve(capture=[]).capture == frozenset()


def test_a_mistyped_capture_switch_keeps_causeline_off():
    assert "unknown capture switch" in resolve(capture=["prompt"])


@pytest.mark.parametrize("variable", ["ENV", "ENVIRONMENT", "APP_ENV", "PYTHON_ENV"])
@pytest.mark.parametrize("value", ["prod", "production", "PRODUCTION", "prod-eu", " Production "])
def test_stays_off_in_production(variable, value):
    assert "production" in resolve(environ={variable: value})


@pytest.mark.parametrize("value", ["", "dev", "qa", "staging", "reproduce"])
def test_other_environments_are_allowed(value):
    assert not isinstance(resolve(environ={"ENV": value}), str)


def test_needs_an_endpoint_and_a_token():
    assert "no endpoint" in resolve_settings(token="t", environ={})
    assert "no access token" in resolve_settings(endpoint="http://localhost:8080", environ={})


def test_settings_can_come_from_the_environment():
    settings = resolve_settings(environ={"CAUSELINE_ENDPOINT": "http://localhost:8080", "CAUSELINE_TOKEN": "t",
                                         "CAUSELINE_SERVICE_NAME": "rag"})
    assert (settings.token, settings.service) == ("t", "rag")


@pytest.mark.parametrize("endpoint", ["http://localhost:8080", "http://localhost:8080/",
                                      "http://localhost:8080/causeline", "http://localhost:8080/causeline/"])
def test_the_endpoint_may_or_may_not_name_causeline(endpoint):
    settings = resolve_settings(endpoint=endpoint, token="t", environ={})
    assert settings.url == "http://localhost:8080/causeline/api/peer-spans"


@pytest.mark.parametrize("endpoint", ["localhost:8080", "ftp://localhost", "http://"])
def test_refuses_an_endpoint_that_is_not_a_url(endpoint):
    assert isinstance(resolve_settings(endpoint=endpoint, token="t", environ={}), str)
