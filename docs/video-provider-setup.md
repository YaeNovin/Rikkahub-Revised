# Video provider setup

## Ark / Seedance (Jimeng)

Use an OpenAI-compatible provider on an Ark host such as
`https://ark.cn-beijing.volces.com/api/v3`, with an authorized Seedance model
or endpoint ID configured as VIDEO. The native task API uses
`POST /contents/generations/tasks` and `GET /contents/generations/tasks/{id}`
relative to that base URL. The app displays supported inputs, duration,
resolution, ratio and advanced options according to `seedanceVideoGenerationConstraints`.
Opaque endpoint IDs cannot prove model capabilities: confirm the actual model
behind the endpoint before relying on its controls.

The independent page stores tasks and polls their status. Download retries reuse
the existing remote task. Reusing parameters submits a new task and may incur
another charge. A task that lost its remote ID is not automatically resubmitted.

## MiniMax Hailuo

The existing MiniMax Anthropic configuration and OpenAI configurations on official
api.minimaxi.com / api.minimax.io / api.minimax.chat hosts can share their API key
with the native video API. Chat protocol configuration is retained.
Add a VIDEO model: MiniMax-Hailuo-2.3, MiniMax-Hailuo-2.3-Fast, MiniMax-Hailuo-02,
T2V-01, T2V-01-Director, I2V-01, I2V-01-Director, or I2V-01-live.
Fast and I2V models accept image input only; T2V models accept text only.
Hailuo supports 768P at 6/10 seconds and 1080P at 6 seconds. Legacy models use
720P at 6 seconds. Prompt optimizer and Hailuo fast pretreatment are exposed.
Prompts are validated at 2000 characters; unsupported size/seed/audio fields are omitted.
The native sequence is POST /v1/video_generation, GET /v1/query/video_generation?task_id=,
then GET /v1/files/retrieve?file_id= to obtain a downloadable URL.
H3 uses a separate API and is not included in this Hailuo adapter. Hailuo-02's
image-only 512P mode and first/last-frame endpoint need additional capability work.

References:
- https://platform.minimax.io/docs/api-reference/video-generation-t2v
- https://platform.minimax.io/docs/api-reference/video-generation-i2v
- https://platform.minimax.io/docs/api-reference/video-generation-query
- https://platform.minimax.io/docs/api-reference/video-generation-download

## xAI / Grok

Use the existing xAI provider with https://api.x.ai/v1 and its API key.
Configure grok-imagine-video or grok-imagine-video-1.5 as a VIDEO model.
These known model identifiers are also selectable on HTTPS OpenAI-compatible
providers, including xai/ and x-ai/ model prefixes. Such gateways must implement
the xAI asynchronous video protocol; model selection does not verify live protocol
compatibility. The configured base path and provider-qualified model ID are retained.
Text-to-video and single-image-to-video are supported. Duration is 1-15 seconds;
1080p is exposed only for 1.5. Audio can be enabled or disabled.
Creation uses POST /v1/videos/generations, polling GET /v1/videos/{request_id}.
Editing, extension and multi-reference generation are not included in this adapter.

Official reference: https://docs.x.ai/developers/model-capabilities/video/generation

## Kling

The built-in Kling provider defaults to https://api-singapore.klingai.com/v1.
API Key must contain AccessKey:SecretKey. The app signs an HS256 JWT locally with
a 30-minute expiry; no signing secret is sent as an HTTP header.
The initial model is kling-v1-6; the model list exposes supported adapter identifiers.
Current scope is text and single first-frame image generation, professional mode
(pro), 5 or 10 seconds, and an optional negative prompt. Resolution is model-defined.
Creation and polling retain the text2video/image2video endpoint family across restarts.
Omni/3.x, multi-shot, extension and sound controls require separate capability work.

Official references:
- https://klingai.com/document-api/api/get-started/authentication
- https://kling.ai/document-api/api/video/1-0/text-to-video
- https://kling.ai/document-api/api/video/1-6

The official site's dynamic pages did not expose full text to extraction in this
session; indexed official excerpts established the base protocol. Real credential
integration and revision-specific capability testing are still required.

## Sui Xiang existing provider

This is an adapter for the existing configuration, not a new provider entry.
Host: sui-xiang.com. Models: as-sd2.0-fast, video-ds-2.0, video-ds-2.0-fast.
Source: provider configuration supplied during development. This route is a
compatibility implementation and has not been verified against a paid live request.

POST /v1/videos sends exactly model, prompt, seconds (string "15"), aspect_ratio.
Ratios: 9:16 and 16:9. Fixed 720p; resolution and other custom fields are omitted.
GET /v1/videos/{id} polls status. GET /v1/videos/{id}/content downloads the result.
Download credentials are attached only to the exact authenticated content endpoint,
not to arbitrary returned media URLs.

## Shared behavior

These adapters advertise no remote cancellation capability; cancellation remains
available before submission and for local downloads. They reuse the durable task
store, history, playback, export and result retry mechanisms. Failed result retries
query the existing task rather than create another paid generation.

No real paid generation was performed during implementation. Unit tests use fixtures
and an intercepted HTTP client; they do not establish live provider compatibility.
