package com.anpesi1.youtube_live_streamer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "youtube_live_streamer/screen_capture"
    private val captureRequestCode = 4101
    private var pendingCapture: Bundle? = null
    private var methodChannel: MethodChannel? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "startCapture" -> {
                    val streamKey = call.argument<String>("streamKey")
                    if (streamKey.isNullOrBlank()) {
                        result.error("INVALID_KEY", "A YouTube stream key is required.", null)
                        return@setMethodCallHandler
                    }

                    pendingCapture = Bundle().apply {
                        putString("streamKey", streamKey)
                        putInt("captureWidth", call.argument<Int>("captureWidth") ?: 1080)
                        putInt("captureHeight", call.argument<Int>("captureHeight") ?: 1920)
                        putInt("cropHeight", call.argument<Int>("cropHeight") ?: 400)
                    }

                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
                    result.success(null)
                }
                "stopCapture" -> {
                    stopService(Intent(this, ScreenCaptureService::class.java))
                    methodChannel?.invokeMethod("captureStopped", null)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequestCode) return

        val pending = pendingCapture
        pendingCapture = null
        if (resultCode != Activity.RESULT_OK || data == null || pending == null) {
            methodChannel?.invokeMethod("captureDenied", null)
            return
        }

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("resultData", data)
            putExtras(pending)
        }
        startForegroundService(serviceIntent)
        methodChannel?.invokeMethod("captureStarted", null)
    }
}