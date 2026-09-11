package com.codemesh.app

import android.app.Application

class CodeMeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Room database singleton here
    }
}
