package com.anpesi1.youtube_live_streamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.ExecuteCallback
import com.arthenica.mobileffmpeg.FFmpeg
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class ScreenCaptureService : Service() {
    companion object {
        private const val NOTIFICATION_CHANNEL = "youtube_live_stream"
        private const val NOTIFICATION_ID = 101
        private const val FRAME_RATE = 15
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var fifo: File? = null
    private var ffmpegExecutionId: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopStreaming()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("resultData", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("resultData")
        }
        val streamKey = intent?.getStringExtra("streamKey")

        if (resultData == null || streamKey.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Connecting to YouTube…"))
        startStreaming(resultCode, resultData, streamKey, intent)
        return START_NOT_STICKY
    }

    private fun startStreaming(
        resultCode: Int,
        resultData: Intent,
        streamKey: String,
        sourceIntent: Intent,
    ) {
        if (!running.compareAndSet(false, true)) return

        val width = sourceIntent.getIntExtra("captureWidth", 1080).coerceAtLeast(2)
        val screenHeight = sourceIntent.getIntExtra("captureHeight", 1920).coerceAtLeast(2)
        val cropHeight = sourceIntent
            .getIntExtra("cropHeight", 400)
            .coerceIn(2, screenHeight)

        val safeKey = streamKey.trim()
        if (!safeKey.matches(Regex("[A-Za-z0-9._-]+"))) {
            running.set(false)
            stopSelf()
            return
        }

        executor.execute {
            try {
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                projection = manager.getMediaProjection(resultCode, resultData)
                imageReader = ImageReader.newInstance(
                    width,
                    screenHeight,
                    PixelFormat.RGBA_8888,
                    3,
                )
                virtualDisplay = projection?.createVirtualDisplay(
                    "YouTube Live website capture",
                    width,
                    screenHeight,
                    resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    null,
                )

                fifo = File(cacheDir, "youtube_live_frames.pipe")
                if (fifo!!.exists()) fifo!!.delete()
                android.system.Os.mkfifo(fifo!!.absolutePath, 0x1B6)

                val command = buildFfmpegCommand(fifo!!.absolutePath, safeKey)
                ffmpegExecutionId = FFmpeg.executeAsync(
                    command,
                    ExecuteCallback { _, returnCode ->
                        if (running.get() && returnCode != Config.RETURN_CODE_SUCCESS) {
                            running.set(false)
                        }
                    },
                )

                FileOutputStream(fifo!!).use { output ->
                    postNotification("LIVE — streaming website preview")
                    while (running.get()) {
                        val image = imageReader?.acquireLatestImage()
                        if (image == null) {
                            SystemClock.sleep(12)
                            continue
                        }

                        try {
                            val bitmap = imageToBitmap(image, width, cropHeight)
                            bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
                            bitmap.recycle()
                            output.flush()
                        } finally {
                            image.close()
                        }
                        SystemClock.sleep(1000L / FRAME_RATE)
                    }
                }
            } catch (_: Exception) {
                running.set(false)
            } finally {
                cleanupCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun buildFfmpegCommand(pipePath: String, streamKey: String): String {
        val destination = "rtmp://a.rtmp.youtube.com/live2/$streamKey"
        return listOf(
            "-y",
            "-loglevel", "error",
            "-f", "image2pipe",
            "-vcodec", "png",
            "-framerate", FRAME_RATE.toString(),
            "-i", pipePath,
            "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-tune", "zerolatency",
            "-pix_fmt", "yuv420p",
            "-r", FRAME_RATE.toString(),
            "-f", "flv",
            destination,
        ).joinToString(" ")
    }

    private fun imageToBitmap(
        image: android.media.Image,
        width: Int,
        cropHeight: Int,
    ): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val fullBitmap = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        fullBitmap.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, min(width, image.width), cropHeight)
        fullBitmap.recycle()
        return cropped
    }

    private fun stopStreaming() {
        if (!running.getAndSet(false)) {
            stopSelf()
            return
        }
        if (ffmpegExecutionId != 0L) {
            FFmpeg.cancel(ffmpegExecutionId)
            ffmpegExecutionId = 0
        }
        cleanupCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupCapture() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        runCatching { fifo?.delete() }
        virtualDisplay = null
        imageReader = null
        projection = null
        fifo = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            "YouTube Live streaming",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("YouTube Live")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun postNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running.set(false)
        if (ffmpegExecutionId != 0L) FFmpeg.cancel(ffmpegExecutionId)
        cleanupCapture()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}