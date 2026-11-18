package com.ashish.allthedocs.cloud

import com.ashish.allthedocs.model.DocumentSource
import io.ktor.http.Url
import kotlinx.serialization.Serializable

sealed interface SearchProvider {
    val displayName: String
    val source: DocumentSource
}

val SearchProvider.accountName: String
    get() = when (this) {
        is CloudProvider -> account.displayName
        is McpServer -> name
    }

enum class CloudAccount(val displayName: String) {
    GOOGLE("Google"),
    MICROSOFT("Microsoft"),
}

enum class CloudProvider(
    override val displayName: String,
    val account: CloudAccount,
    override val source: DocumentSource,
) : SearchProvider {
    GMAIL("Gmail", CloudAccount.GOOGLE, DocumentSource.GMAIL),
    GOOGLE_DRIVE("Google Drive", CloudAccount.GOOGLE, DocumentSource.GOOGLE_DRIVE),
    OUTLOOK("Outlook", CloudAccount.MICROSOFT, DocumentSource.OUTLOOK),
    ONEDRIVE("OneDrive", CloudAccount.MICROSOFT, DocumentSource.ONEDRIVE),
}

@Serializable
data class McpServer(val id: String, val name: String, val url: String) : SearchProvider {
    override val displayName: String get() = name
    override val source: DocumentSource get() = DocumentSource.CONNECTED_APP
    val host: String get() = Url(url).host
}

data class CloudFile(
    val provider: SearchProvider,
    val id: String,
    val parentId: String?,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val context: String?,
    val modified: String?,
    val locators: Map<String, String> = emptyMap(),
)

fun interface AccessTokenSource {
    suspend fun accessToken(account: CloudAccount): String
}

class CloudAuthException(message: String) : Exception(message)

open class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause)
