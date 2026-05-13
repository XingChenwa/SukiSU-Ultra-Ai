# Requirements Document

## Introduction

This feature adds an **AI-assisted KPM plugin authoring experience** to the SukiSU-Ultra manager app. The user — typically a kernel-savvy developer or advanced ROM/mod author — describes the desired behaviour in natural language (e.g. "hook `__arm64_sys_openat` to log accesses to `/data/system/packages.xml`"), and the manager drives a user-configured Large Language Model (LLM) to produce a complete KPM source tree that matches the KPM loader contract implemented in `kernel/kpm/kpm.c` and the conventions captured in `docs/guide/how-to-integrate.md` and `.cursor/rules/general.mdc`.

The feature is inspired by the `dugongzi/JsxposedX` project's "AI-assisted Xposed plugin authoring" flow, but is adapted to SukiSU-Ultra's native C-based KPM module ecosystem (as opposed to JsxposedX's script/Java/Frida-leaning targets). Because KPM modules run inside the kernel, the feature places strong emphasis on safety guardrails, reviewability, and explicit user consent before any generated artifact is loaded.

The scope of this spec covers the **manager-side** (Kotlin + Jetpack Compose) UX, LLM transport, prompt/response handling, persistence, project scaffolding, and safety policy. Kernel-side changes are out of scope — generated KPM modules run through the existing `sukisu_kpm_load_module_path` path unchanged.

## Glossary

- **KPM (Kernel Patch Module)**: A native kernel module conforming to the SukiSU-Ultra KPM loader ABI exposed by `kernel/kpm/kpm.c`. Loaded via `sukisu_kpm_load_module_path`.
- **KPM Project**: A directory containing one or more C/H source files, a build script (e.g. `Makefile` or `Kbuild`), a `kpm.toml` metadata manifest, and optional README / license files, organised so that it builds into a loadable `.kpm` artifact.
- **Manager**: The Android application under `manager/app/`, package `com.sukisu.ultra`.
- **AI_Assistant**: The new subsystem added by this feature. Composed of the `AI_Screen` (Compose UI), `AI_Session` (conversation state), `AI_Provider_Client` (HTTP transport), `Prompt_Builder`, `Response_Parser`, `Project_Scaffolder`, `Safety_Policy`, and `Key_Vault`.
- **AI_Provider**: A user-configured remote or local LLM endpoint. Providers are OpenAI-compatible HTTP/JSON endpoints at minimum (configurable base URL, model id, API key, optional headers). A provider may be marked as Cloud or Local.
- **AI_Session**: A persisted, ordered sequence of turns (user message + assistant message + tool calls) associated with one KPM Project draft.
- **AI_Provider_Client**: The Kotlin module responsible for performing HTTP requests against an AI_Provider, handling streaming, cancellation, and transport errors. Built on OkHttp (already a project dependency).
- **Prompt_Builder**: A pure component that composes the system prompt, KPM reference context (ABI definitions, sample snippets, project coding rules), user turns, and any retrieved code snippets into a provider-ready request payload.
- **Response_Parser**: A pure component that consumes assistant output (possibly streamed, possibly interleaved with markdown) and extracts a structured `GeneratedProject` (files, diffs, metadata, safety flags). Treated as an untrusted parser for the purposes of this spec.
- **Project_Scaffolder**: The component that materialises a `GeneratedProject` onto the device filesystem under the app's private storage as a KPM Project.
- **Safety_Policy**: The ruleset and decision engine that classifies a request or a generated artifact as allowed, blocked, or gated-on-confirmation. Rules include disallowed intent categories (fraud, competitive-game cheating, credential theft, mass surveillance, bypassing device attestation to defraud services, malware) and technical red flags (writes to arbitrary user-supplied kernel addresses, disabling SELinux globally without user acknowledgement, etc.).
- **Key_Vault**: The storage layer for AI_Provider credentials. Uses the Android Keystore-backed `EncryptedSharedPreferences` (or equivalent) so raw keys never appear in plaintext on disk.
- **GeneratedProject**: The structured output of a generation turn — a manifest plus an ordered list of file entries with paths, contents, and a language hint.
- **kpm.toml**: The per-project manifest file recording name, version, target kernel ABI, generator metadata (model id, session id, generation timestamp), and a declared capability set (e.g. `reads_syscalls`, `patches_memory`).
- **Cloud Provider**: An AI_Provider whose requests traverse the public internet.
- **Local Provider**: An AI_Provider whose base URL resolves to a loopback or RFC1918 address and whose requests stay on the device or local network.
- **Round-trip Property**: A property asserting `parse(serialize(x)) ≡ x` for all `x` in the input domain. Used here for `kpm.toml` and for the `GeneratedProject` file manifest parser.

## Requirements

### Requirement 1: Configure AI Providers

**User Story:** As a KPM developer, I want to configure one or more AI providers with my own API keys, so that I can use the LLM of my choice (cloud or self-hosted) without the manager app shipping any hard-coded vendor credentials.

#### Acceptance Criteria

1. THE AI_Assistant SHALL NOT ship with any pre-populated API key, provider secret, or vendor-specific endpoint that performs LLM inference on behalf of the user.
2. WHEN the user opens the AI provider settings screen, THE AI_Assistant SHALL display the list of configured AI_Providers, each showing provider name, base URL, model id, and whether the provider is marked as Cloud or Local.
3. WHEN the user adds a new AI_Provider, THE AI_Assistant SHALL require the fields `name`, `baseUrl`, `modelId`, `apiKey`, and `providerKind` (`Cloud` or `Local`) before the provider can be saved.
4. WHEN the user saves an AI_Provider, THE Key_Vault SHALL persist the `apiKey` using Android Keystore-backed encrypted storage.
5. THE Key_Vault SHALL NOT write any `apiKey` value to plaintext SharedPreferences, the app's unencrypted files directory, logcat, crash reports, or any file included in bug-report bundles.
6. WHEN the user edits an existing AI_Provider, THE AI_Assistant SHALL preserve the previously stored `apiKey` if the `apiKey` field is left blank in the edit form.
7. WHEN the user deletes an AI_Provider, THE Key_Vault SHALL remove the associated `apiKey` and THE AI_Assistant SHALL remove the provider entry from persisted settings.
8. IF a `baseUrl` is not a syntactically valid absolute URL using scheme `http` or `https`, THEN THE AI_Assistant SHALL reject the save operation and display the validation error identifier `ai_provider_invalid_base_url`.
9. IF a `baseUrl` uses scheme `http` and resolves to a non-loopback, non-RFC1918 host, THEN THE AI_Assistant SHALL reject the save operation and display the validation error identifier `ai_provider_insecure_transport`.
10. WHEN the user selects one AI_Provider as the active default, THE AI_Assistant SHALL persist that selection and use it as the default provider for subsequent AI_Sessions.

### Requirement 2: Start and Continue AI Sessions

**User Story:** As a KPM developer, I want to start a chat-style conversation with the AI about a new or existing KPM project, so that I can iteratively describe what I want the plugin to do.

#### Acceptance Criteria

1. WHEN the user opens the AI_Assistant entry point from the manager's navigation, THE AI_Assistant SHALL display the list of existing AI_Sessions ordered by last-updated timestamp descending.
2. WHEN the user creates a new AI_Session, THE AI_Assistant SHALL create a new AI_Session record with a unique session id, a human-readable title, a reference to a new KPM Project draft, and the currently active AI_Provider.
3. WHEN the user submits a message in an AI_Session, THE AI_Assistant SHALL append the message to the session turn log with role `user` and a monotonic turn index before dispatching the request to the AI_Provider_Client.
4. WHEN the AI_Provider_Client receives a successful response, THE AI_Assistant SHALL append the response to the session turn log with role `assistant` and the same monotonic turn index sequence.
5. WHILE a request is in flight for an AI_Session, THE AI_Assistant SHALL display a cancel control and SHALL cancel the underlying HTTP call when the user activates it, using structured-concurrency cancellation (no `GlobalScope`).
6. WHERE the active AI_Provider supports server-sent streaming, THE AI_Assistant SHALL render partial assistant output incrementally as chunks are received.
7. IF a request fails due to a transport, authentication, rate-limit, or server error, THEN THE AI_Assistant SHALL record the failure reason on the turn and display a localized error message keyed by provider error class (`ai_error_auth`, `ai_error_rate_limit`, `ai_error_transport`, `ai_error_server`, `ai_error_unknown`).
8. WHEN the user reopens an AI_Session after the process has been killed and restarted, THE AI_Assistant SHALL restore the complete turn log and the associated KPM Project draft from persistent storage.
9. THE AI_Assistant SHALL support at least the languages already supported by the manager's string resources at the time of implementation (currently including at minimum `en`, `zh-rCN`, `zh-rTW`, `zh-rHK`, `ja`, `ru`, `tr`, `tl`, `te`, `vi`, `uk`, `fa`, `fr`).

### Requirement 3: Generate a KPM Project

**User Story:** As a KPM developer, I want the AI to produce a complete, buildable KPM project layout, so that I do not have to assemble the scaffolding (manifest, Kbuild/Makefile, entry points) by hand.

#### Acceptance Criteria

1. WHEN the user requests project generation in an AI_Session, THE Prompt_Builder SHALL include the canonical KPM ABI reference derived from `kernel/kpm/kpm.c` and `kernel/kpm/kpm.h` in the system prompt.
2. WHEN the user requests project generation, THE Prompt_Builder SHALL include the project coding rules from `.cursor/rules/general.mdc` for languages C and C++ in the system prompt.
3. WHEN the Response_Parser processes an assistant turn that claims to contain a GeneratedProject, THE Response_Parser SHALL extract a manifest and a list of file entries, each with non-empty `path`, `content`, and `language` fields.
4. FOR ALL GeneratedProject values `p`, `parseProjectManifest(serializeProjectManifest(p)) ≡ p` (round-trip property on the `kpm.toml` serializer/parser).
5. FOR ALL syntactically well-formed assistant payloads `s` produced by the serializer, `Response_Parser.parse(s)` SHALL succeed and yield a GeneratedProject equal to the one that produced `s` (round-trip property on the wire format between Prompt_Builder and Response_Parser).
6. IF the assistant payload is syntactically malformed, THEN THE Response_Parser SHALL return a descriptive error value identifying the offending location, and SHALL NOT partially apply any files.
7. WHEN a GeneratedProject is applied by the Project_Scaffolder, THE Project_Scaffolder SHALL write every file under the KPM Project draft directory atomically: either all files are present at their declared paths or the directory is rolled back to its pre-apply state.
8. THE Project_Scaffolder SHALL reject any file entry whose `path` contains `..` segments, absolute path prefixes, or any path that resolves outside the KPM Project draft directory, with error identifier `ai_scaffold_path_traversal`.
9. WHEN a GeneratedProject is applied, THE Project_Scaffolder SHALL write a `kpm.toml` manifest containing the session id, the model id, the generation timestamp, and the declared capability set.
10. WHEN the user invokes project re-generation in the same AI_Session, THE Project_Scaffolder SHALL present a diff between the prior and the new GeneratedProject and SHALL require explicit user confirmation before overwriting existing files.

### Requirement 4: Review, Diff, and Edit Generated Code

**User Story:** As a KPM developer, I want to review every file the AI produced, see diffs, and edit the code in-app, so that I can verify and refine the output before building or loading anything.

#### Acceptance Criteria

1. WHEN the user opens a KPM Project draft, THE AI_Assistant SHALL display the file tree of the project and SHALL allow the user to open any file for read-only viewing by default.
2. WHEN the user enables edit mode on a file, THE AI_Assistant SHALL provide a text editor with syntax awareness for C, C header, Makefile, and TOML content.
3. WHEN the user saves edits to a file, THE AI_Assistant SHALL persist the change and SHALL mark the file as user-modified in the project manifest.
4. WHEN the user requests a diff view, THE AI_Assistant SHALL display a line-level diff between the last AI-generated version of the file and the current on-disk version.
5. WHERE a file has been user-modified, THE Prompt_Builder SHALL include the current on-disk content — not the original AI-generated content — in subsequent turns of the same AI_Session.

### Requirement 5: Export and Hand-off to Build

**User Story:** As a KPM developer, I want to export the project to a ZIP archive or to a local directory, so that I can build it with an external kernel toolchain on my workstation.

#### Acceptance Criteria

1. WHEN the user selects "Export project as archive", THE AI_Assistant SHALL produce a ZIP archive containing every file in the KPM Project draft, preserving relative paths.
2. WHEN the user selects "Export project to directory" and grants a destination URI via Storage Access Framework, THE AI_Assistant SHALL copy every file in the KPM Project draft to the destination, preserving relative paths.
3. FOR ALL KPM Project drafts `d`, `unzip(zipExport(d))` SHALL produce a file set equal to `d` with identical paths and byte-for-byte identical contents (round-trip property on the archive exporter).
4. IF an export destination is not writable, THEN THE AI_Assistant SHALL abort the export with error identifier `ai_export_destination_unwritable` and SHALL NOT leave a partial archive behind.
5. THE AI_Assistant SHALL include a generated `README.md` in the exported project that restates the safety disclaimer defined in Requirement 9 in the current UI language.

### Requirement 6: Optional In-App Syntax Validation

**User Story:** As a KPM developer, I want the manager to run a cheap on-device syntax check on generated C code, so that I catch obvious AI mistakes before exporting.

#### Acceptance Criteria

1. WHERE an on-device C tokenizer is available, WHEN the user triggers "Validate project", THE AI_Assistant SHALL run a best-effort syntactic validation on every `.c` and `.h` file in the KPM Project draft.
2. WHEN validation completes, THE AI_Assistant SHALL display a per-file pass/fail status and, for each failure, the first offending line number and a localized reason code.
3. THE AI_Assistant SHALL NOT claim that a passing validation result implies the project is safe to load; the status label SHALL use the wording corresponding to string identifier `ai_validation_syntax_only`.

### Requirement 7: Load-to-Kernel Requires Explicit Consent

**User Story:** As a KPM developer, I want loading an AI-generated KPM into the live kernel to be a deliberate, explicit act, so that I never accidentally run unreviewed AI code inside my kernel.

#### Acceptance Criteria

1. IF the user attempts to load a KPM artifact whose `kpm.toml` identifies it as AI-generated, THEN THE AI_Assistant SHALL require the user to pass a confirmation dialog whose accept action is disabled until the user has scrolled through the full list of declared capabilities.
2. WHEN the confirmation dialog is accepted, THE AI_Assistant SHALL hand off the artifact path to the existing KPM load path (`loadKpmModule`) unchanged; THE AI_Assistant SHALL NOT bypass, replace, or duplicate the existing loader.
3. THE AI_Assistant SHALL log, into the app's local action log, the session id, the model id, the artifact hash, and the user action (`loaded` or `declined`) each time the confirmation dialog is presented.

### Requirement 8: Safety Policy Enforcement

**User Story:** As a project maintainer, I want the feature to refuse to assist with clearly abusive use cases, so that the manager does not become a turnkey tool for fraud, cheating, or malware authoring.

#### Acceptance Criteria

1. WHEN the user submits a message whose intent matches a Safety_Policy disallowed category (competitive-game cheating, credential or token theft, bypassing device attestation to defraud services, mass surveillance of third parties, ransomware-style destructive payloads), THE Safety_Policy SHALL block the request before it leaves the device and SHALL display the localized rationale keyed by `ai_safety_blocked_<category>`.
2. WHEN a GeneratedProject contains a technical red flag (e.g. a write to a kernel address derived from an unvalidated user-supplied integer, or code that disables SELinux enforcement without a user-visible toggle in `kpm.toml`), THE Safety_Policy SHALL mark the project as gated-on-confirmation and THE AI_Assistant SHALL require an extra confirmation before the Project_Scaffolder writes files.
3. THE Safety_Policy SHALL record every block or gate decision with the matched rule id and the truncated, hashed request digest, and THE AI_Assistant SHALL make these records available for export to the user.
4. IF the user reports a false-positive block, THEN THE AI_Assistant SHALL allow the user to submit a one-line justification and retry the request; THE Safety_Policy SHALL re-evaluate and either allow with gating or maintain the block with a distinct rationale.
5. THE Safety_Policy SHALL fail closed: if the policy engine throws an unhandled exception, THE AI_Assistant SHALL treat the request as blocked and display error identifier `ai_safety_engine_error`.

### Requirement 9: Disclaimers and Licensing

**User Story:** As a user, I want clear, upfront disclaimers so that I understand the AI output is unverified and that I am responsible for what I load into the kernel.

#### Acceptance Criteria

1. WHEN the user enters the AI_Assistant for the first time on a device, THE AI_Assistant SHALL display a modal disclaimer explaining that generated code is unverified, that KPM modules run inside the kernel, that the user is solely responsible for inspection and consequences, and that network use of Cloud Providers transmits prompts to third parties.
2. THE AI_Assistant SHALL require the user to acknowledge the disclaimer before any request can be sent to any AI_Provider for the first time.
3. THE AI_Assistant SHALL make the disclaimer text re-accessible at any time from the AI_Assistant settings screen.
4. THE Project_Scaffolder SHALL include the project's declared `SPDX-License-Identifier` in every generated C/H file header, defaulting to `GPL-2.0-or-later` to match the project's existing KPM code unless the user has selected a different SPDX identifier in the project settings.

### Requirement 10: Quotas and Cost Awareness

**User Story:** As a user of paid AI providers, I want the manager to show me token usage and to let me cap spending, so that I do not rack up surprise bills.

#### Acceptance Criteria

1. WHEN an AI_Provider response includes token-usage metadata, THE AI_Assistant SHALL persist `promptTokens`, `completionTokens`, and `totalTokens` on the corresponding turn.
2. THE AI_Assistant SHALL display cumulative token totals per AI_Session and per AI_Provider on the settings screen.
3. WHERE the user has configured a per-session token cap, IF the next request's estimated token count plus the session's cumulative total would exceed the cap, THEN THE AI_Assistant SHALL block the request with error identifier `ai_quota_session_exceeded`.
4. THE quota check SHALL be deterministic: for a fixed cap, cumulative total, and estimated token count, the block/allow decision SHALL be stable across repeated invocations.

### Requirement 11: Round-Trip Correctness for Wire and Manifest Formats

**User Story:** As a maintainer, I want the AI payload parser and the `kpm.toml` serializer to be provably round-trip-safe, so that the project format stays stable as the feature evolves.

#### Acceptance Criteria

1. FOR ALL `GeneratedProject` values produced by the Prompt_Builder's declared output schema, `Response_Parser.parse(Prompt_Builder.renderExpectedOutput(p)) ≡ p`.
2. FOR ALL `ProjectManifest` values `m`, `parseTomlManifest(serializeTomlManifest(m)) ≡ m`.
3. FOR ALL `AI_Session` values `s`, `deserialize(serialize(s)) ≡ s`.
4. THE AI_Assistant SHALL include a Pretty_Printer for `ProjectManifest` that produces human-readable, deterministic output for a given manifest value.

### Requirement 12: Idempotent Apply and Deterministic Identity

**User Story:** As a user, I want re-applying the same GeneratedProject to leave the KPM Project draft in the same state, so that retries after a transient failure are safe.

#### Acceptance Criteria

1. FOR ALL GeneratedProject values `g` and KPM Project draft directories `d`, `apply(apply(g, d)) ≡ apply(g, d)` (idempotence of Project_Scaffolder apply).
2. FOR ALL GeneratedProject values `g`, `artifactHash(g)` SHALL be stable under reordering of files within the manifest (confluence of hashing).
3. WHEN a Project_Scaffolder apply fails partway, THE Project_Scaffolder SHALL restore the KPM Project draft directory to its pre-apply state.

### Requirement 13: Privacy — What Leaves the Device

**User Story:** As a privacy-conscious user, I want the manager to never silently send device identifiers to an AI_Provider, so that I retain control over what my prompts reveal.

#### Acceptance Criteria

1. THE AI_Provider_Client SHALL NOT include Android `ANDROID_ID`, IMEI, serial number, advertising id, installed package list, user accounts, or any file outside the current AI_Session in any request payload.
2. WHEN the user enables "Include project files as context", THE Prompt_Builder SHALL include only files from the current KPM Project draft and SHALL display a preview of the exact payload before the request is sent.
3. IF the active AI_Provider is a Cloud Provider, THEN THE AI_Assistant SHALL display a visible indicator on the input area that the request will leave the device.
4. THE AI_Provider_Client SHALL NOT send a request to any URL other than the `baseUrl` of the currently selected AI_Provider plus the OpenAI-compatible path suffix required for chat completions.

### Requirement 14: Offline and Local Provider Support

**User Story:** As a user running a local LLM, I want the feature to work with on-device or LAN providers (llama.cpp, Ollama, vLLM), so that I can keep all prompts local.

#### Acceptance Criteria

1. WHERE an AI_Provider is configured with `providerKind = Local`, THE AI_Assistant SHALL allow `baseUrl` to use scheme `http` with a loopback or RFC1918 host without triggering the `ai_provider_insecure_transport` error defined in Requirement 1.
2. WHERE the only configured AI_Providers are Local and none is reachable, THE AI_Assistant SHALL display error identifier `ai_local_provider_unreachable` and SHALL NOT fall back to any Cloud Provider implicitly.
3. THE AI_Assistant SHALL expose a "Test connection" action per AI_Provider that performs a minimal chat completion request and reports success, failure class, and round-trip latency.

### Requirement 15: Observability and Reproducibility

**User Story:** As a developer diagnosing a bad generation, I want enough recorded context to reproduce the run, so that I can report bugs and iterate on prompts.

#### Acceptance Criteria

1. THE AI_Session record SHALL persist, for each assistant turn, the request's `modelId`, `temperature`, `topP` (if set), `seed` (if set), the prompt template id, and the full rendered system prompt hash.
2. WHEN the user invokes "Export session for reporting", THE AI_Assistant SHALL produce a JSON bundle containing turn metadata, prompt hashes, safety decisions, and — only if the user confirms — full prompt/response bodies with API keys redacted.
3. THE exported JSON bundle SHALL be round-trip-safe: `parse(serialize(bundle)) ≡ bundle`.
