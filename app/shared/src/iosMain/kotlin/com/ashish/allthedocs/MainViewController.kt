package com.ashish.allthedocs

import androidx.compose.ui.window.ComposeUIViewController
import androidx.room.Room
import com.ashish.allthedocs.cloud.IosCloudAuth
import com.ashish.allthedocs.cloud.PlatformCloudAuth
import com.ashish.allthedocs.data.db.AppDatabase
import com.ashish.allthedocs.data.db.DATABASE_NAME
import com.ashish.allthedocs.data.db.buildAppDatabase
import com.ashish.allthedocs.di.initKoin
import com.ashish.allthedocs.platform.DocumentViewer
import com.ashish.allthedocs.platform.ExpiryReminderScheduler
import com.ashish.allthedocs.platform.IosDocumentViewer
import com.ashish.allthedocs.platform.IosExpiryReminderScheduler
import com.ashish.allthedocs.platform.IosPlatformBridge
import com.ashish.allthedocs.platform.IosSecureFileStore
import com.ashish.allthedocs.platform.IncomingDocuments
import com.ashish.allthedocs.platform.PickedDocument
import com.ashish.allthedocs.platform.toCapturedFile
import com.ashish.allthedocs.model.DocumentSource
import org.koin.mp.KoinPlatform
import com.ashish.allthedocs.platform.SecureFileStore
import com.ashish.allthedocs.platform.applicationSupportDirectory
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import org.koin.dsl.module
import platform.UIKit.UIViewController

private var koinStarted = false

fun MainViewController(bridge: IosPlatformBridge): UIViewController {
    if (!koinStarted) {
        initKoin(iosPlatformModule(bridge))
        koinStarted = true
    }
    return ComposeUIViewController { App() }
}

fun deliverIncomingDocument(document: PickedDocument): String? =
    document.toCapturedFile(DocumentSource.SHARED).fold(
        onSuccess = {
            KoinPlatform.getKoin().get<IncomingDocuments>().offer(it)
            null
        },
        onFailure = { it.message ?: "Couldn't read the shared file" },
    )

private fun iosPlatformModule(bridge: IosPlatformBridge) = module {
    single<IosPlatformBridge> { bridge }
    single<AppDatabase> {
        Room.databaseBuilder<AppDatabase>(name = applicationSupportDirectory() + "/" + DATABASE_NAME)
            .buildAppDatabase()
    }
    single<HttpClientEngine> { Darwin.create() }
    single<SecureFileStore> { IosSecureFileStore(bridge) }
    single<ExpiryReminderScheduler> { IosExpiryReminderScheduler() }
    single<DocumentViewer> { IosDocumentViewer(bridge) }
    single<PlatformCloudAuth> { IosCloudAuth(bridge) }
}
