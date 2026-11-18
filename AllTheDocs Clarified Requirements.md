# AllTheDocs: Clarified Requirements

Oct 15, 2026 · @Ashish Sah

## Context

The user describes a real-life scenario. AllTheDocs then works out which personal or official documents it needs, and finds and verifies them across the user's own sources.

The repo is a fresh Kotlin Multiplatform template with no product code yet. It has `:app:androidApp`, `:app:shared` (Compose Multiplatform for Android and iOS), `:core`, and a Ktor `:server`. This doc records the answers from two clarification rounds, which are the input for the MVP design plan.

## Status

The milestone 1 core loop and the DigiLocker-style vault are built and run end to end on the Android emulator. The email and drive connectors are built and wait for OAuth client IDs, and any cloud service with a remote MCP server can be added as a connected app. The app module's 67 unit tests pass on the Android host (26 new for MCP connectors); core 51 and server 73 are unchanged.

| Feature | Status | Notes |
| --- | --- | --- |
| Scenario → follow-up questions → checklist | Done | Curated India rules work without an AI key. Other scenarios use Groq with web search |
| Resume an unfinished scenario | Built, not run-tested | The description, follow-up questions and answers are saved on the device as you type and listed under Continue where you left off. Database v3 → v4. Unit-tested; not run on a device |
| Curated India rules (6 scenarios) | Done | Passport, home loan, personal loan, savings account, ITR (salaried), job onboarding |
| Common-scenario quick start | Done | Cards on the Scenarios screen jump straight to the curated questions |
| Encrypted on-device vault | Done | AES-GCM files. Android Keystore key; iOS Keychain + CryptoKit |
| DigiLocker-style vault | Done | Issued and Uploaded tabs, categories, search, issuer, Verify now |
| In-app DigiLocker (you sign in, downloads are captured) | Built, not run-tested | DigiLocker's site and login page load in the app on Android. A full sign-in and download needs your account |
| Share into AllTheDocs | Done | Android share sheet tested end to end; iOS "Open in" built |
| Digital-signature check (India CCA roots) | Built, not run-tested | Signed, tampered, unsigned and untrusted cases tested with a test PKI; not yet tried on a real DigiLocker PDF |
| AI document verification (Groq vision) | Built, not run-tested | Mock-tested only. Needs `GROQ_API_KEY` in `server/.env` for a live run |
| Document scanner, Android (ML Kit) | Done | Scan → signature check → saved to vault, tested on the emulator |
| Document scanner, iOS (VisionKit) | Built, not run-tested | Built; not run |
| Expiry reminders (30 and 7 days before) | Done | Android jobs scheduled at the right delays; a forced run posted the notification. iOS notifications not run |
| iOS app | Built, not run-tested | Launches on the simulator and loads scenarios from the server. Tap-driven flows (scanner, picker, DigiLocker, preview, sign-in) not run |
| Gmail / Outlook attachment search | Built, not run-tested | Search per checklist item with on-device OAuth (PKCE). Provider calls mock-tested; Google sign-in launches on Android and cancel is handled. Needs OAuth client IDs |
| Google Drive / OneDrive search | Built, not run-tested | Same connector; needs OAuth client IDs |
| Connected apps (MCP connectors) | Built, not run-tested | Any remote MCP server from Settings → Connected apps: OAuth discovery (RFC 9728/8414), dynamic client registration or your own client ID and secret, PKCE in the browser, tokens on the device. Search and download go through the server's tools. Dropbox and Box presets. Mock-tested only; no real server tried yet |
| Secrets config (`secrets.properties`, `server/.env`) | Done | Git-ignored files with `.example` templates; the app build generates `AppSecrets` from them |
| DigiLocker API connector | Blocked | Partner API needs a registered organisation (no GSTIN). Replaced by in-app DigiLocker plus the signature check |

## Changelog

| Date | Update | What changed |
| --- | --- | --- |
| 2026-10-20 | Connected apps (MCP connectors) | Any cloud service with a remote MCP server can be added under Settings → Connected apps and searched from a checklist item next to Gmail, Drive, Outlook and OneDrive. The app probes the server, discovers its OAuth endpoints (RFC 9728/8414), registers itself when the server allows it or uses your client ID and secret, signs in with PKCE in the browser, and keeps tokens on the device. Search calls the server's search tool; download uses resources/read, a download tool, or a download link it returns. Dropbox and Box presets plus any custom URL. 26 new unit tests (client, discovery and sign-in, search); Android and iOS compile. Not tried against a real server |
| 2026-10-20 | Resume unfinished scenarios | Finished scenarios were already stored on the device, but an in-progress one lived only in memory. It is now saved to the local database as you type (description, follow-up questions, answers) and shown under Continue where you left off on the Scenarios screen; opening it restores the form, finishing or discarding it removes the draft. Database v3 → v4 by auto-migration. 4 new unit tests; Android and iOS compile. Not yet run on a device |
| 2026-10-15 | Connectors, runtime checks, iOS launch | Gmail, Google Drive, Outlook and OneDrive search from a checklist item, with on-device OAuth (PKCE; Google through Play services on Android, ASWebAuthenticationSession on iOS) and Connected accounts in Settings. secrets.properties and server/.env for keys. Runtime checks on Android: ML Kit scan saved to the vault, a reminder notification fired, Google sign-in launched and cancel handled. iOS app launched on the simulator |
| 2026-10-15 | DigiLocker without an API key | In-app DigiLocker (Android WebView / iOS WKWebView) captures your downloads into the review screen. Share and Open in from any app. Server checks PDF digital signatures against the bundled India CCA roots; a valid signature puts a document in Issued without AI. Database v2 → v3 |
| 2026-10-15 | DigiLocker access without an API key | Partner API needs a registered organisation (no GSTIN). Assessed an agent that logs in for the user: not recommended (credential handling, likely against the terms, fragile). Recommended: the user logs in to DigiLocker inside the app and we capture their downloads, plus share-to-app import and a digital-signature check for Issued. Not built yet |
| 2026-10-15 | Progress tracking | Added the Status and Changelog sections; this doc is updated after every prompt |
| 2026-10-15 | Quick start + DigiLocker-style vault | Common-scenario cards from a new curated-scenarios endpoint. Vault split into Issued/Uploaded with categories, search, issuing authority and Verify now. Database v1 → v2 auto-migration checked on a device with existing data |
| 2026-10-15 | Milestone 1 core loop | KMP app (Android + iOS) and Ktor server: curated India rules, Groq-backed requirements and verification, encrypted vault, scanners, expiry reminders. Checked end to end on the Android emulator |
| 2026-10-15 | Requirements clarified | Two question rounds; decisions recorded below |

## Decisions

v1 is a cross-platform personal project. Documents are fetched on the device, verified briefly on a stateless server, and kept in an encrypted on-device vault.

| Area | Decision |
| --- | --- |
| Doc type | Personal/official paperwork: IDs, certificates, statements, payslips, forms |
| Scope | Global, open-ended scenarios |
| Requirement knowledge | Hybrid: a curated rules DB for known scenarios, plus an LLM with web search for the rest |
| Seed rules | India first. Visa/travel, loans & banking, tax filing, job/education |
| Citations | Best effort: cite official sources when available, don't block on it |
| Scenario refinement | Ask follow-up questions before generating the checklist |
| Sources | DigiLocker, email attachments (Gmail/Outlook), cloud drives (Drive/iCloud/OneDrive), on-device files & photos |
| Fetching | On device. Tokens never leave the phone |
| "Extract" in v1 | Find & collect files, then verify each is the right doc (classification, validity/expiry, name match). No field extraction or form auto-fill |
| Output | Checklist with found/missing/needs-action status |
| Missing docs | How to obtain each one: steps, official portal links, typical fees and timelines |
| Vault | On-device and encrypted. Reuse across scenarios, expiry reminders, manual add/scan |
| Server processing | Each file is sent only for verification and never persisted |
| Platforms | Android + iOS + Ktor server |
| LLM | Groq free tier for v1, behind a pluggable provider interface so another free provider can be swapped in |
| Auth | No accounts. The server is stateless |
| Goal | Personal/side project with no hard deadline and real integrations |
| Language | English only for v1 |
| Common scenarios | Quick-start cards for the curated scenarios at the top of the Scenarios screen |
| Vault layout | DigiLocker-style vault with no external service. Issued = an official document type, verified with no issues; everything else is Uploaded |
| Unfinished scenarios | Saved on the device only, in the app database; nothing is sent to the server until the checklist is requested |
| Connected apps (MCP) | The app is an MCP client on the device: OAuth 2.1 discovery, dynamic client registration or a user-supplied client ID, PKCE, tokens never leave the phone. Presets only for Dropbox and Box; Google Drive and OneDrive stay on the built-in connectors. Only files a server returns as PDF, JPEG or PNG can be added |

## Risks

DigiLocker access is the biggest integration risk for a personal project. The others have workable fallbacks.

| Risk | Impact | Fallback |
| --- | --- | --- |
| DigiLocker API needs partner-organisation registration | An individual project may not get access | A DigiLocker-style local vault is built. No API connector yet; DigiLocker PDFs can be added through the file picker |
| Gmail/Drive restricted OAuth scopes | An unverified app works for up to 100 test users. A public release needs CASA verification | Fine for a personal project, so revisit if it goes public |
| iCloud has no general third-party API | Can't search iCloud programmatically | Use the iOS Files picker or document provider |
| Global, open-ended scope relies on live web lookup | Requirements can be stale, wrong or uncited | Flag uncited items as unverified, and prefer curated rules where they exist |
| Docs sent to the server for verification carry PII | Leaks through logs or LLM provider retention | No persistence or logging of file content. Choose an LLM provider with zero data retention |
| MCP servers differ in tool names and result shapes | Search or download may find no usable tool, or parse nothing, on a given server | The client matches search and download tools by name and common field names, mock-tested only. Try Dropbox and Box with real credentials; add a per-server tool mapping if a popular server needs it |

## Next steps

- [ ] Fill in `server/.env` (`GROQ_API_KEY`) and `secrets.properties` (`GOOGLE_IOS_CLIENT_ID`, `MICROSOFT_CLIENT_ID`). Google: enable the Gmail and Drive APIs, add yourself as a test user, create an Android client (package `com.ashish.allthedocs`, debug SHA-1 `67:D3:98:D1:12:8F:BF:CA:92:8E:87:93:CE:AD:F5:64:48:F4:86:74`) and an iOS client (bundle `com.ashish.allthedocs.AllTheDocs`). Microsoft: register a public client with redirect `com.ashish.allthedocs://oauth/microsoft` and `Mail.Read`, `Files.Read`, `User.Read`, `offline_access`
- [ ] With the keys in: connect Google and Microsoft, search a checklist item in each provider, and verify a real document with Groq
- [ ] Sign in to DigiLocker in the app, download one issued document, and confirm its signature shows Valid and it lands in Issued
- [ ] On iOS: try the scanner, file picker, DigiLocker, preview, Open in and account sign-in on the simulator
- [ ] On the emulator: start a scenario, answer one question, kill the app, reopen and continue it from the Scenarios screen
- [ ] Add Dropbox (App key) or Box (client ID + secret) under Settings → Connected apps, connect it, and search a checklist item through it; if search or download fails, note the tools the server lists
