package com.ashish.allthedocs.cloud

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidCloudAuth(private val context: Context) : PlatformCloudAuth {
    override val handlesGoogleNatively: Boolean = true

    override val browser: BrowserAuthenticator = object : BrowserAuthenticator {
        override suspend fun authenticate(url: String, redirectUri: String): String {
            val deferred = OAuthRedirects.register(redirectUri)
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return try {
                deferred.await()
            } finally {
                OAuthRedirects.unregister(redirectUri, deferred)
            }
        }
    }

    override suspend fun googleAccessToken(interactive: Boolean): String {
        val client = Identity.getAuthorizationClient(context)
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(OAuthConfigs.GOOGLE_SCOPES.map(::Scope))
            .build()
        val result = client.authorize(request).await()
        if (!result.hasResolution()) {
            return result.accessToken ?: throw CloudAuthException("Google didn't return an access token")
        }
        if (!interactive) throw CloudAuthException("Google needs your approval again. Connect Google.")
        val pendingIntent = result.pendingIntent ?: throw CloudAuthException("Google sign-in couldn't start")
        val activityResult = AuthProxyActivity.launch(context, pendingIntent)
        if (activityResult.resultCode != Activity.RESULT_OK) throw CloudAuthException("Google sign-in was cancelled")
        return client.getAuthorizationResultFromIntent(activityResult.data).accessToken
            ?: throw CloudAuthException("Google didn't return an access token")
    }

    override suspend fun googleSignOut() = Unit
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { error ->
        continuation.resumeWithException(CloudAuthException(error.message ?: "Google sign-in failed"))
    }
    addOnCanceledListener { continuation.cancel() }
}

object OAuthRedirects {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun register(redirectUri: String): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()
        pending.put(redirectUri, deferred)?.cancel()
        return deferred
    }

    fun unregister(redirectUri: String, deferred: CompletableDeferred<String>) {
        pending.remove(redirectUri, deferred)
    }

    fun complete(uri: Uri): Boolean {
        val key = pending.keys.firstOrNull { uri.toString().startsWith(it) } ?: return false
        return pending.remove(key)?.complete(uri.toString()) ?: false
    }
}

class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(OAuthRedirects::complete)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}

class AuthProxyActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        intent.getStringExtra(EXTRA_KEY)?.let { results.remove(it)?.complete(result) }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val pendingIntent = IntentCompat.getParcelableExtra(intent, EXTRA_PENDING_INTENT, PendingIntent::class.java)
        if (pendingIntent == null) {
            intent.getStringExtra(EXTRA_KEY)?.let { results.remove(it)?.complete(ActivityResult(RESULT_CANCELED, null)) }
            finish()
            return
        }
        launcher.launch(IntentSenderRequest.Builder(pendingIntent).build())
    }

    companion object {
        private const val EXTRA_KEY = "key"
        private const val EXTRA_PENDING_INTENT = "pending_intent"
        private val results = ConcurrentHashMap<String, CompletableDeferred<ActivityResult>>()

        suspend fun launch(context: Context, pendingIntent: PendingIntent): ActivityResult {
            val key = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<ActivityResult>()
            results[key] = deferred
            context.startActivity(
                Intent(context, AuthProxyActivity::class.java)
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_PENDING_INTENT, pendingIntent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return try {
                deferred.await()
            } finally {
                results.remove(key)
            }
        }
    }
}
