package com.kasem.receive_sharing_intent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.Build
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.NewIntentListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLConnection
import java.util.concurrent.Executors
import kotlin.io.copyTo

private const val MESSAGES_CHANNEL = "receive_sharing_intent/messages"
private const val EVENTS_CHANNEL_MEDIA = "receive_sharing_intent/events-media"
private const val EVENTS_CHANNEL_TEXT = "receive_sharing_intent/events-text"

class ReceiveSharingIntentPlugin : FlutterPlugin, ActivityAware, MethodCallHandler,
        EventChannel.StreamHandler, NewIntentListener {

    private var initialIntent: Intent? = null
    private var latestMedia: JSONArray? = null

    private var eventSinkMedia: EventChannel.EventSink? = null

    private var binding: ActivityPluginBinding? = null
    private lateinit var applicationContext: Context

    private fun setupCallbackChannels(binaryMessenger: BinaryMessenger) {
        val mChannel = MethodChannel(binaryMessenger, MESSAGES_CHANNEL)
        mChannel.setMethodCallHandler(this)

        val eChannelMedia = EventChannel(binaryMessenger, EVENTS_CHANNEL_MEDIA)
        eChannelMedia.setStreamHandler(this)

        val eChannelText = EventChannel(binaryMessenger, EVENTS_CHANNEL_TEXT)
        eChannelText.setStreamHandler(this)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        setupCallbackChannels(binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSinkMedia = events
    }

    override fun onCancel(arguments: Any?) {
        eventSinkMedia = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "getInitialMedia" -> {
                val intent = initialIntent
                initialIntent = null
                
                if (intent == null) {
                    result.success(null)
                    return
                }
                
                val mainHandler = Handler(Looper.getMainLooper())
                
                // Process in background thread to avoid blocking UI
                Executors.newSingleThreadExecutor().execute {
                    try {
                        val media = getMediaUris(intent)
                        // Post result back to main thread
                        mainHandler.post {
                            result.success(media?.toString())
                        }
                    } catch (e: Exception) {
                        Log.e("SharingIntent", "Error processing initial media", e)
                        // Post error back to main thread
                        mainHandler.post {
                            result.error("PROCESSING_ERROR", "Failed to process initial media: ${e.message}", null)
                        }
                    }
                }
            }
            "reset" -> {
                initialIntent = null
                latestMedia = null
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun handleIntent(intent: Intent, initial: Boolean) {
        when {
            // Sharing or opening media (image, video, text, file)
            intent.type != null && (
                    intent.action == Intent.ACTION_VIEW
                            || intent.action == Intent.ACTION_SEND
                            || intent.action == Intent.ACTION_SEND_MULTIPLE) -> {

                val value = getMediaUris(intent)
                if (!initial) {
                    latestMedia = value
                    eventSinkMedia?.success(latestMedia?.toString())
                }
            }

            // Opening URL
            intent.action == Intent.ACTION_VIEW -> {
                val value = JSONArray(
                        listOf(JSONObject()
                                .put("path", intent.dataString)
                                .put("type", MediaType.URL.value))
                )
                if (!initial) {
                    latestMedia = value
                    eventSinkMedia?.success(latestMedia?.toString())
                }
            }

            // Opening email contact
            intent.scheme?.startsWith("mailto") == true &&
                    intent.action == Intent.ACTION_SENDTO -> {
                val value = JSONArray(
                        listOf(JSONObject()
                                .put("path", intent.data?.schemeSpecificPart)
                                .put("type", MediaType.MAILTO.value))
                )
                if (!initial) {
                    latestMedia = value
                    eventSinkMedia?.success(latestMedia?.toString())
                }
            }
        }
    }

    private fun getMediaUris(intent: Intent?): JSONArray? {
        if (intent == null) return null

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                toJsonObject(uri, null, intent.type)?.let { JSONArray(listOf(it)) }
            }

            Intent.ACTION_SEND -> {
                val uri = intent.parcelable<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                toJsonObject(uri, text, intent.type)?.let { JSONArray(listOf(it)) }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM)
                val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)

                uris?.mapIndexedNotNull { index, uri ->
                    toJsonObject(uri, text, mimeTypes?.getOrNull(index))
                }?.let { JSONArray(it) }
            }

            else -> null
        }
    }

    // content can only be uri or string
    private fun toJsonObject(uri: Uri?, text: String?, mimeType: String?): JSONObject? {
        val path = uri?.let { 
            // First try to get path directly
            val directPath = FileDirectory.getAbsolutePath(applicationContext, it)
            if (directPath != null) {
                // If path is in /sdcard, copy to cache for Android 13+ compatibility
                if (directPath.startsWith("/sdcard/") || directPath.startsWith("/storage/emulated/")) {
                    copyFileToCache(directPath) ?: directPath
                } else {
                    directPath
                }
            } else {
                // If that fails, try copying URI to temp file (for content:// URIs)
                copyUriToTempFile(it)
            }
        }
        val specifiedMimeType = text?.let { "text" } ?: mimeType
        val mType = specifiedMimeType ?: path?.let { URLConnection.guessContentTypeFromName(path) }
        val type = MediaType.fromMimeType(mType)
        val (thumbnail, duration) = path?.let { getThumbnailAndDuration(path, type) }
                ?: Pair(null, null)
        return JSONObject()
                .put("path", path ?: text)
                .put("type", type.value)
                .put("mimeType", mType)
                .put("thumbnail", thumbnail)
                .put("duration", duration)
                .put("text", text)
    }

    // Get video thumbnail and duration.
    private fun getThumbnailAndDuration(path: String, type: MediaType): Pair<String?, Long?> {
        if (type != MediaType.VIDEO) return Pair(null, null) // get thumbnail and duration for video only
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)
        val duration = retriever.extractMetadata(METADATA_KEY_DURATION)?.toLongOrNull()
        val bitmap = retriever.getScaledFrameAtTime(-1, OPTION_CLOSEST_SYNC, 360, 360)
        retriever.release()
        if (bitmap == null) return Pair(null, null)
        val targetFile = File(applicationContext.cacheDir, "${File(path).name}.png")
        FileOutputStream(targetFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return Pair(targetFile.path, duration)
    }

    // Copy file from external storage to app cache directory (for Android 13+ compatibility)
    private fun copyFileToCache(filePath: String): String? {
        Log.d("SharingIntent", "copyFileToCache filePath=$filePath")
        return try {
            val sourceFile = File(filePath)
            if (!sourceFile.exists()) {
                Log.d("SharingIntent", "Source file does not exist: $filePath")
                return null
            }
            
            val fileName = sourceFile.name
            val tempFile = File(applicationContext.cacheDir, fileName)
            Log.d("SharingIntent", "copyFileToCache copying to ${tempFile.absolutePath}")
            
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d("SharingIntent", "copyFileToCache success: ${tempFile.absolutePath}")
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e("SharingIntent", "copyFileToCache error", e)
            e.printStackTrace()
            null
        }
    }

    // Copy content URI to temp file and return the path
    private fun copyUriToTempFile(uri: Uri): String? {
        Log.d("SharingIntent", "copyUriToTempFile uri=$uri")
        return try {
            val contentResolver = applicationContext.contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            
            // Get file name from URI or use a default
            val fileName = getFileName(uri) ?: "shared_file_${System.currentTimeMillis()}"
            val tempFile = File(applicationContext.cacheDir, fileName)
            Log.d("SharingIntent", "copyUriToTempFile created ${tempFile.absolutePath}")
            FileOutputStream(tempFile).use { output ->
                inputStream.copyTo(output)
            }
            
            tempFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Helper to get filename from URI
    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = applicationContext.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let { 
                val cut = it.lastIndexOf('/')
                if (cut != -1) {
                    it.substring(cut + 1)
                } else {
                    null
                }
            }
        }
        return result
    }

    enum class MediaType(val value: String) {
        IMAGE("image"), VIDEO("video"), TEXT("text"), FILE("file"), URL("url"), MAILTO("mailto");

        companion object {
            fun fromMimeType(mimeType: String?): MediaType {
                return when {
                    mimeType?.startsWith("image") == true -> IMAGE
                    mimeType?.startsWith("video") == true -> VIDEO
                    mimeType?.equals("text") == true -> TEXT
                    else -> FILE
                }
            }
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addOnNewIntentListener(this)
        binding.activity.intent?.let {
            initialIntent = it
            handleIntent(it, true)  // Process initial intent early
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        binding?.removeOnNewIntentListener(this)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addOnNewIntentListener(this)
    }

    override fun onDetachedFromActivity() {
        binding?.removeOnNewIntentListener(this)
    }

    override fun onNewIntent(intent: Intent): Boolean {
        handleIntent(intent, false)
        return true  // Tell Android we handled the intent
    }

    inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = when {
        Build.VERSION.SDK_INT >= 33 -> getParcelableExtra(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
    }

    inline fun <reified T : Parcelable> Intent.parcelableArrayList(key: String): ArrayList<T>? = when {
        Build.VERSION.SDK_INT >= 33 -> getParcelableArrayListExtra(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelableArrayListExtra(key)
    }
}
