# Implementation Plan: Custom Grok OAuth Improvements

## Scope
Port compatible upstream Grok model-catalog merging, model-specific reasoning, and weekly/monthly quota parsing into the fork's custom multi-account OAuth architecture without restoring the incompatible AI-layer Grok provider.

## Decisions
- Keep account selection, token refresh, image generation, and storage in `app/data/grok`.
- Use the upstream CLI and xAI catalog/billing endpoints only where the current custom OAuth token and headers are compatible.
- Keep Imagine models in discovery even though upstream filters `imagine` as non-chat.
- Expose quota through `ProviderQuota` while retaining the existing account usage snapshot/UI behavior.

## Tasks
- [x] Inspect custom provider, OAuth manager, repository, quota model, and Response API.
- [x] Inspect upstream Grok catalog, reasoning, and quota algorithms.
- [x] Port resilient dual-catalog model discovery.
- [x] Port model-specific reasoning effort filtering.
- [x] Port weekly/monthly quota parsing and provider integration.
- [x] Add focused parser/reasoning tests.
- [ ] Run focused tests, full tests, formatting, and build checks.

## Risks
- Upstream `ProviderSetting.Grok` is an account subtype and cannot be used directly with the fork's custom multi-account Grok setting.
- Billing response shapes are external and must remain strictly limited to fields observed in upstream code.
