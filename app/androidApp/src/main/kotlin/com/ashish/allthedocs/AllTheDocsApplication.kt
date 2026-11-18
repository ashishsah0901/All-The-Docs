package com.ashish.allthedocs

import android.app.Application
import com.ashish.allthedocs.di.startAllTheDocs

class AllTheDocsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startAllTheDocs(this)
    }
}
