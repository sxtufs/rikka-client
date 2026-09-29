# OAuth Provider Tasks

## Phase 1: Foundation
- [ ] Add `Codex`, `Grok`, and `GeminiOAuth` provider settings and provider-manager dispatch.
  - Acceptance: settings serialize with stable discriminators and `getProviderByType` resolves all three.
  - Verify: `./gradlew :ai:test` and compile checks.
- [ ] Add stable built-in provider entries and app DI registration points.
  - Acceptance: fresh settings contain disabled built-in entries and no credentials are stored in settings.
  - Verify: settings/provider unit tests.

## Phase 2: Authentication and providers
- [ ] Implement Codex encrypted account store, OAuth manager, repository, provider transport, and callback handling.
  - Acceptance: PKCE login, encrypted persistence, refresh, account rotation, model listing, and streaming work.
  - Verify: mocked OAuth/HTTP/SSE tests.
- [ ] Implement Grok encrypted account store, device OAuth, repository, and provider transport.
  - Acceptance: device login, refresh, account rotation, model listing, and streaming work.
  - Verify: mocked OAuth/HTTP/SSE tests.
- [ ] Implement Gemini Code Assist/Antigravity account store, OAuth onboarding, repository, and streaming provider.
  - Acceptance: sign-in provisions a project, model listing and streaming work, and the terms warning is visible.
  - Verify: mocked onboarding/HTTP/SSE tests.

## Phase 3: UI and hardening
- [ ] Add provider-specific settings and account-management UI.
  - Acceptance: sign-in, refresh, enable/disable, logout, and status/error states are usable.
  - Verify: compile and manual settings walkthrough.
- [ ] Add regression tests and documentation for privacy, terms risks, and unsupported account states.
  - Acceptance: no secret-bearing logs/backups and all new behavior is covered.
  - Verify: `./gradlew test && ./gradlew assembleDebug && ./gradlew lint`.

## Checkpoint: Before provider implementation
- [ ] Review foundation changes before adding network/auth implementations.
