# Video generation implementation status

Current source status: 2026-09-12. The original implementation notes below refer
to the 2026-09-07 milestone; they are not a claim of live provider verification.

## Current provider scope

Ark/Seedance, xAI/Grok, Kling and MiniMax/Hailuo adapters are present, along with
the specific Sui Xiang compatible route. Setup, accepted model IDs, supported
parameters and remaining limits are documented in [video-provider-setup.md](video-provider-setup.md).
User-configurable arbitrary field mapping is still pending.
Generation keepalive and foreground notifications reduce interruptions but do
not guarantee that Android or a vendor ROM will keep the process alive.

## Implemented

- Video model type, modality labels, and Ark Seedance provider dispatch.
- Persistent task and output records, delayed polling, startup recovery.
- Dedicated video page in the sidebar media menu.
- Basic/advanced parameter tabs with horizontal swipe and model capability filtering.
- Text, image, and first/last-frame input controls.
- Durable local image copies for background requests.
- Explicit submission confirmation with cost, duration, and resolution information.
- History, status, error text, parameter reuse, cancellation, result retry, batch record deletion.
- Media3 fullscreen playback with seeking and aspect-preserving layout.
- SAF export to a user-selected file location.
- Explicit save to a new conversation belonging to the current assistant, including model metadata.
- Generated video request compaction; copied output stays outside automatic attachment backup directories.
- Optional user-authored character description, world summary, and shot script.
- Shared MediaGenerationService submission entry for the image page and video page.
- Completion/failure notifications when notification permission is available.

## Corrections in this iteration

- An interrupted submission without a remote ID is not automatically resubmitted.
- Result retry preserves the existing remote task; parameter reuse creates a new task.
- Download cancellation uses a valid local state and checks coroutine cancellation while copying.
- Provider HTTP errors are classified instead of treating every IOException as retryable.
- Default image references survive request-state normalization.
- Download responses reject explicit non-video MIME types and verify existing local output files.
- Concurrent invalid state transitions return false instead of throwing.

## Still outstanding

- Configurable compatible-provider endpoints and request/result field mapping.
- Officially verified, revision-specific Seedance capability matrix, including opaque endpoint profiles.
- Video/audio references, video extension, and the associated input controls.
- A user-adjustable cache quota and dedicated cache-management controls (thumbnail cache currently has a fixed 32 MiB limit).
- Full task diagnostics with aggregated timings and retries.
- Full migration of existing image lifecycle and persistence into the shared service; currently only submission is shared.
- Worker/database concurrency, process-death, expired-link, and interrupted-download integration tests.
- Emulator/device verification of paging, playback, file picker, notifications, and background recovery.

## Verification

## Follow-up implementation

- Added inline Media3 playback in history and chat, local thumbnails with a 32 MiB cache limit, and lifecycle pause.
- Added user-selected assistant prompt and worldbook description import into editable fields.
- Added task detail disclosure with provider, task ID, elapsed time and retry count.
- Result download retries re-query the existing remote task and preserve already downloaded files.
- Submission cancellation records intent while allowing the task ID response to finish; recovery without an ID reports an unknown result.
- Startup removes stale partial downloads older than 24 hours, preserving completed files.
- The original milestone deferred additional providers. The adapters listed at
  the top of this document have since been added; arbitrary field mapping remains deferred.

- The original milestone passed `:app:compileQaKotlin` (Kotlin compilation).
- Nine selected app unit tests passed (request state, task state, filename, historical media policy).
- No real paid API request, APK assembly, or device installation was performed in this iteration.
- Compilation does not establish runtime WorkManager dependency injection or end-to-end generation correctness.
