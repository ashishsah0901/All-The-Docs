This is a Kotlin Multiplatform project targeting Android, iOS, Server.

* [/app/iosApp](./app/iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### AllTheDocs server configuration

The server resolves document requirements and verifies documents through any OpenAI-compatible LLM API (Groq by default).
Without a key, only the curated India scenarios work and verification is disabled.

| Variable | Default |
| --- | --- |
| `GROQ_API_KEY` / `LLM_API_KEY` | none |
| `LLM_BASE_URL` | `https://api.groq.com/openai/v1` |
| `LLM_TEXT_MODEL` | `openai/gpt-oss-120b` |
| `LLM_SEARCH_MODEL` | `openai/gpt-oss-120b` (with `browser_search`) |
| `LLM_VISION_MODEL` | `qwen/qwen3.8-27b` |
| `PORT` | `8080` |

| `SIGNATURE_TRUST_DIR` | none (extra `.cer`/`.pem` roots for the PDF signature check, on top of the bundled India CCA roots) |
| `ENV_FILE` | `.env`, then `server/.env` |

Put secrets in `server/.env` (git-ignored, see `server/.env.example`); real environment variables win over the file.

The Android emulator reaches the server at `http://10.0.2.2:8080` and the iOS simulator at `http://localhost:8080`.
On a physical device, change the server URL in the app's Settings tab.

### Connected accounts (Gmail, Google Drive, Outlook, OneDrive)

The app searches your email attachments and drives for a checklist item and fetches the file straight to the device
(read-only scopes, tokens stay on the device). Fill in `secrets.properties` (git-ignored, see `secrets.properties.example`)
and rebuild:

1. **Google**: in Google Cloud Console enable the Gmail API and Google Drive API, configure the OAuth consent screen
   (External, add yourself as a test user) and create OAuth clients:
   - Android: package `com.ashish.allthedocs` plus your debug signing SHA-1
     (`keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`).
     No ID needs to be pasted anywhere; Play services finds it from the package and fingerprint.
   - iOS: bundle ID `com.ashish.allthedocs.AllTheDocs`. Put the client ID in `GOOGLE_IOS_CLIENT_ID`.
2. **Microsoft**: in Entra ID register an app for "Personal Microsoft accounts and any org", add a
   *Mobile and desktop* redirect URI `com.ashish.allthedocs://oauth/microsoft`, enable public client flows, and grant the
   delegated permissions `User.Read`, `Mail.Read`, `Files.Read`, `offline_access`. Put the Application (client) ID in
   `MICROSOFT_CLIENT_ID`.

### Connected apps (MCP)

Any cloud service with a remote [MCP](https://modelcontextprotocol.io) server can be searched the same way. In Settings →
Connected apps, pick Dropbox or Box (or enter any server URL), add a client ID and secret if the service needs them, and tap
Connect. The app discovers the server's OAuth endpoints (RFC 9728 / RFC 8414), registers itself when the server allows
dynamic client registration, signs you in through the browser with PKCE, and keeps the tokens on the device. Searching uses
the server's `search*` tool; downloading uses `resources/read` or its `download*`/`*content` tool, so only servers that
return the original file (PDF, JPEG, PNG) or a download link can add documents.

| Preset | Server | Needs |
| --- | --- | --- |
| Dropbox | `https://mcp.dropbox.com/mcp` | A Dropbox app's App key as the client ID unless Dropbox has allow-listed the app |
| Box | `https://mcp.box.com` | Client ID and secret from Admin Console → Integrations → Box MCP server |

Register the redirect URI `com.ashish.allthedocs://oauth/mcp` with the service. Google Drive and OneDrive are already covered
by the built-in connectors above.

### DigiLocker

There is no partner API key (it needs a registered organisation). Instead, the Add document screen opens DigiLocker in
an in-app browser: you sign in yourself and the document you download is captured into the vault. You can also share a
file from the DigiLocker app into AllTheDocs. The server checks each PDF's digital signature against India's CCA root
certificates; a valid signature puts the document in the vault's *Issued* section.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Server: `./gradlew :server:run`
- iOS app: open the [/app/iosApp](./app/iosApp) directory in Xcode and run it from there.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…