package com.copyeye.app

import android.app.Application
import android.content.Context
import com.copyeye.app.capture.MediaProjectionController
import com.copyeye.app.clipboard.ClipboardHistoryRepository
import com.copyeye.app.clipboard.ClipboardWriter
import com.copyeye.app.core.common.DeviceCapabilities
import com.copyeye.app.core.common.Haptics
import com.copyeye.app.core.permissions.PermissionChecker
import com.copyeye.app.data.preferences.SettingsRepository
import com.copyeye.app.ocr.MlKitTextRecognitionEngine
import com.copyeye.app.ocr.TextRecognitionEngine

/**
 * Manual dependency container.
 *
 * A single-module app with nine collaborators does not need a dependency-injection framework. Hilt
 * would add a code generator, a plugin, and a class of build failure to a project whose whole
 * wiring graph fits on one screen — and the overlay service, which is the piece most likely to be
 * awkward to inject into, is constructed by the system rather than by us.
 *
 * The lazy delegates matter: the OCR engine loads native models, and an app opened only to change a
 * setting should never pay for that.
 */
class AppContainer(private val application: Application) {

    private val context: Context get() = application

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }

    val deviceCapabilities: DeviceCapabilities by lazy { DeviceCapabilities(context) }

    val permissionChecker: PermissionChecker by lazy { PermissionChecker(context) }

    val haptics: Haptics by lazy { Haptics(context) }

    val clipboardWriter: ClipboardWriter by lazy { ClipboardWriter(context) }

    val historyRepository: ClipboardHistoryRepository by lazy {
        ClipboardHistoryRepository(context)
    }

    /**
     * Shared across the whole process on purpose: it holds the projection grant, and Android 14
     * allows exactly one virtual display per grant, so a second instance could never work.
     */
    val projectionController: MediaProjectionController by lazy {
        MediaProjectionController(context)
    }

    val textRecognitionEngine: TextRecognitionEngine by lazy { MlKitTextRecognitionEngine() }

    /** Called when the process is trimmed hard; native OCR handles are the expensive part. */
    fun releaseHeavyResources() {
        textRecognitionEngine.close()
    }
}
