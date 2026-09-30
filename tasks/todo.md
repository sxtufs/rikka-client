# OAuth Provider Tasks

> Source implementation has been rebuilt in the current working tree. The checklist remains open until Gradle tests/build/lint are run on a machine with the Android toolchain.

## Phase 1 — Foundation
- [ ] Replace the partial provider port with one coherent settings/manager contract.
  - Acceptance: `codex`, `grok`, and `gemini_oauth` serialize stably; dispatch resolves only registered implementations; OAuth types cannot be converted to API-key providers.
  - Verify: `./gradlew :ai:test`.
- [ ] Make generic OAuth callback/PKCE primitives lifecycle-safe.
  - Acceptance: state is checked, loopback callbacks are single-use, sessions can keep the callback foreground service alive, and token HTTP does not log secrets.
  - Verify: `./gradlew :oauth:test`.

## Phase 2 — Account/authentication
- [ ] Rebuild Codex encrypted account repository and PKCE manager.
  - Acceptance: authorization-code login, refresh-token rotation, encrypted persistence, account rotation, invalidation and usage parsing are covered.
  - Verify: Codex repository/parser tests.
- [ ] Rebuild Grok device-code account repository and manager.
  - Acceptance: RFC 8628 polling handles pending/slow-down/expiry/denial, refresh preserves old refresh tokens, and token errors are sanitized.
  - Verify: Grok OAuth/repository/parser tests.
- [ ] Rebuild Gemini OAuth account repository and onboarding.
  - Acceptance: Google OAuth uses loopback+PKCE, project discovery/onboarding is bounded, expired access tokens refresh, and missing-project errors are actionable.
  - Verify: Gemini endpoint/account tests.

## Phase 3 — Provider transport
- [ ] Implement Codex Responses transport and model/usage discovery.
  - Acceptance: official Codex URL/headers are fixed, request stream mode is forced, missing SSE media type is adapted only for the Codex client, and tool/reasoning mapping works.
- [ ] Implement Grok Responses/image transport.
  - Acceptance: OAuth bearer is used only for xAI endpoints, model/image responses parse safely, and partial SSE streams are not duplicated by retries.
- [ ] Implement Gemini Code Assist model and wrapped SSE transport.
  - Acceptance: `{project, model, request}` envelope, endpoint fallback/backoff, nested error parsing, and shared Gemini message conversion work.
  - Verify: focused provider tests plus `./gradlew :app:test`.

## Phase 4 — UI and hardening
- [ ] Add usable sign-in/status/logout/refresh controls and preserve disabled/default-provider behavior.
  - Acceptance: no credentials appear in provider settings or share/export payloads; login errors are visible without token data; Gemini terms warning is shown.
- [ ] Run full verification and review the diff.
  - Verify: `./gradlew test`, `./gradlew assembleDebug`, `./gradlew lint`.

## Checkpoint
- [ ] No implementation continues past a failing focused test or compile error.
- [ ] Manual OAuth smoke tests are performed only after the local test/build gates pass.
