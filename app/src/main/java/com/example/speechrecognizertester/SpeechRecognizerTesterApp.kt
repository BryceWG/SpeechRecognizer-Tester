/*
 * speechrecognizer-tester: Application 初始化（动态配色等）
 */
package com.example.speechrecognizertester

import android.app.Application
import com.google.android.material.color.DynamicColors

class SpeechRecognizerTesterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}

