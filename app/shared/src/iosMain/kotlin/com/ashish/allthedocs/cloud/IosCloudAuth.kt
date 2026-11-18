package com.ashish.allthedocs.cloud

import com.ashish.allthedocs.platform.IosPlatformBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class IosCloudAuth(private val bridge: IosPlatformBridge) : PlatformCloudAuth {
    override val handlesGoogleNatively: Boolean = false

    override val browser: BrowserAuthenticator = object : BrowserAuthenticator {
        override suspend fun authenticate(url: String, redirectUri: String): String = suspendCancellableCoroutine { continuation ->
            bridge.authenticate(url, redirectUri.substringBefore(':')) { callbackUrl, error ->
                if (!continuation.isActive) return@authenticate
                if (callbackUrl != null) {
                    continuation.resume(callbackUrl)
                } else {
                    continuation.resumeWithException(CloudAuthException(error ?: "Sign-in was cancelled"))
                }
            }
        }
    }

    override suspend fun googleAccessToken(interactive: Boolean): String =
        throw CloudAuthException("Google sign-in uses the browser flow on iOS")

    override suspend fun googleSignOut() = Unit
}
