# Implementation Plan: OAuth Providers (Codex, Grok, Gemini Code Assist)

## Objective
Replace the incomplete provider port with a coherent Android implementation for three account-backed providers:

- **Codex**: ChatGPT OAuth authorization-code + PKCE, encrypted multi-account credentials, refresh, model discovery, Responses API streaming and usage headers.
- **Grok**: xAI/Grok CLI OAuth device-code flow, encrypted multi-account credentials, refresh, OpenAI-compatible Responses API transport and image generation.
- **Gemini OAuth**: Google installed-app OAuth with loopback + PKCE, encrypted credentials, Cloud Code Assist onboarding/project discovery, model discovery and wrapped Gemini SSE streaming.

OAuth credentials must never be serialized into normal provider settings, exported with provider configuration, or written to logs. The existing API-key providers remain unchanged.

## Architecture Decisions
1. `ai` owns provider contracts and serializable provider-setting discriminators only.
2. `oauth` owns generic PKCE, callback-server, browser-launch and token HTTP primitives.
3. `app` owns account stores, refresh/rotation policy, provider transports and UI/DI because credentials require Android Keystore and app persistence.
4. Credentials are encrypted with Android Keystore AES-GCM in `noBackupFilesDir`; provider settings contain only model selections and enabled/name state.
5. OAuth callbacks use the loopback server's foreground-service-aware session API so the process can survive switching to a browser.
6. OAuth provider requests use fixed official endpoints and headers internally; users cannot redirect subscription credentials to arbitrary API-compatible URLs.

## Scope and Boundaries
- Always: redact bearer/form credentials, validate OAuth state, use PKCE where supported, refresh before expiry, close OkHttp responses, preserve refresh tokens when providers omit rotation, and add unit tests for parsers/retry/account selection.
- Ask first: changing provider protocol constants, adding a new OAuth account type, or storing credentials outside Keystore-backed files.
- Never: put access/refresh tokens in `ProviderSetting`, backups, normal logs, request bodies outside the intended OAuth endpoint, or user-configurable base URLs.
- Explicit limitation: these providers depend on provider-controlled or non-public subscription endpoints and may stop working or be subject to provider terms; the UI must state this for Gemini OAuth and errors must be actionable without exposing tokens.

## Verification Commands
- Focused: `./gradlew :oauth:test :ai:test :app:test --tests '*OAuth*' --tests '*Codex*' --tests '*Grok*' --tests '*Gemini*'`
- Full JVM tests: `./gradlew test`
- Build: `./gradlew assembleDebug`
- Static checks: `./gradlew lint`

## Phases
1. **Foundation** — provider settings, manager dispatch, default provider migration, OAuth module primitives.
2. **Authentication** — common callback lifecycle, Codex PKCE, Grok device flow, Gemini OAuth/onboarding, encrypted repositories.
3. **Transport** — model discovery, token refresh, Responses/Gemini request mapping, SSE/error/retry behavior.
4. **UI and hardening** — login/status/account controls, secret redaction, serialization/import safety, regression tests.

## Risks and Mitigations
| Risk | Impact | Mitigation |
|---|---|---|
| Provider endpoints or client fingerprints change | High | Centralize constants, keep error bodies sanitized, add endpoint/parser tests, document unsupported responses |
| Android process is killed while browser is open | High | Use the existing OAuth callback foreground service session API |
| Refresh-token rotation loses a session | High | Keep the old refresh token when response omits a replacement and serialize atomically |
| Provider credentials leak through settings/export/logging | High | Separate encrypted account repositories; never place tokens in ProviderSetting |
| Partial SSE streams are retried and duplicated | Medium | Retry only before downstream chunks are emitted; normalize missing media type only for Codex/Grok clients |
| Gemini account has no Cloud Code project | High | Bounded load/onboard/reload flow and an explicit actionable error |
