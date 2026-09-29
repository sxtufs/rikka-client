# Implementation Plan: Codex, Grok, and Gemini OAuth Providers

## Overview
Port the authentication-backed provider functionality from ExTV/rikkahub-agent into the official RikkaHub fork without importing ExTV's unrelated agent-mode changes.

## Scope
- Codex: ChatGPT OAuth with PKCE, loopback callback, encrypted multi-account credentials, refresh, model listing, Responses API chat/streaming.
- Grok: xAI device authorization OAuth, encrypted credentials, refresh, model listing, Responses API chat/streaming.
- Gemini OAuth: Google/Antigravity Code Assist OAuth, project onboarding, encrypted credentials, model listing, streaming generation, and a prominent terms-of-service warning.
- Shared: provider settings, DI registration, Android callback/deep-link handling, account UI, tests, and documentation.

## Architecture Decisions
- Keep credentials out of `ProviderSetting`; store them in Android Keystore-backed AES/GCM files under `noBackupFilesDir`.
- Reuse the existing `:oauth` module and existing `ResponseAPI`/Google request parsing where compatible.
- Register app-owned providers through `DataSourceModule` rather than coupling the `:ai` module to `:app` classes.
- Port selected ExTV provider code and fixes only; do not merge the ExTV fork.

## Build and Verification Commands
- `./gradlew test`
- `./gradlew assembleDebug`
- `./gradlew lint`

## Project Structure
- `ai/src/main/.../provider/` — provider settings and provider lookup.
- `app/src/main/.../data/codex|grok|gemini/` — OAuth, credential stores, repositories, and transports.
- `app/src/main/.../di/DataSourceModule.kt` — provider construction and registration.
- `app/src/main/.../ui/pages/setting/` — provider configuration and account management UI.
- `app/src/test` and `ai/src/test` — unit and protocol parsing tests.

## Testing Strategy
- Unit-test PKCE/state, encrypted-store recovery, token refresh, account selection, JSON model mapping, and error classification.
- Use mocked HTTP/SSE responses; never use real provider credentials in tests.
- Run the full Gradle test, assemble, and lint commands before completion.

## Boundaries
- Always: preserve upstream behavior, redact credentials from logs, use tests for new parsing/auth logic, and keep secrets out of backups.
- Ask first: adding new third-party dependencies, changing application id/signing, or changing the scope to include unrelated ExTV agent features.
- Never: commit OAuth tokens/client secrets, bypass OAuth state validation, or silently enable risky Gemini Code Assist access.

## Success Criteria
- Each provider can be enabled from the RikkaHub settings UI and has an account sign-in flow.
- Tokens survive app restarts, refresh automatically, and are not included in backups or logs.
- Model listing and streaming text generation work with a mocked protocol test suite.
- Existing OpenAI, Google API-key, Claude, and custom provider behavior remains unchanged.
- Debug APK builds successfully and all tests/lint pass.

## Open Questions
- Whether Grok image generation should be included in the first implementation or deferred until chat/auth is stable.
- Whether localization should cover every new account-management string in the first pass.
