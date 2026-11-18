package com.ashish.allthedocs.di

import android.content.Context
import androidx.room.Room
import com.ashish.allthedocs.cloud.AndroidCloudAuth
import com.ashish.allthedocs.cloud.PlatformCloudAuth
import com.ashish.allthedocs.data.db.AppDatabase
import com.ashish.allthedocs.data.db.DATABASE_NAME
import com.ashish.allthedocs.data.db.buildAppDatabase
import com.ashish.allthedocs.platform.AndroidDocumentViewer
import com.ashish.allthedocs.platform.AndroidSecureFileStore
import com.ashish.allthedocs.platform.DocumentViewer
import com.ashish.allthedocs.platform.ExpiryReminderScheduler
import com.ashish.allthedocs.platform.SecureFileStore
import com.ashish.allthedocs.platform.WorkManagerExpiryReminderScheduler
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidPlatformModule = module {
    single<AppDatabase> {
        val context = androidContext()
        Room.databaseBuilder<AppDatabase>(context, context.getDatabasePath(DATABASE_NAME).absolutePath)
            .buildAppDatabase()
    }
    single<HttpClientEngine> { OkHttp.create() }
    single<SecureFileStore> { AndroidSecureFileStore(androidContext()) }
    single<ExpiryReminderScheduler> { WorkManagerExpiryReminderScheduler(androidContext()) }
    single<DocumentViewer> { AndroidDocumentViewer(androidContext()) }
    single<PlatformCloudAuth> { AndroidCloudAuth(androidContext()) }
}

fun startAllTheDocs(context: Context) {
    initKoin(androidPlatformModule) {
        androidContext(context)
    }
}
