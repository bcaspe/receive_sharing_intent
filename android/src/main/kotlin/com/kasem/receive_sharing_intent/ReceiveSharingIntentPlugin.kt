package com.kasem.receive_sharing_intent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.URLConnection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "ReceiveSharingIntent"
private const val MESSAGES_CHANNEL = "receive_sharing_intent/messages"
private const val EVENTS_CHANNEL_MEDIA = "receive_sharing_intent/events-media"
private const val EVENTS_CHANNEL_TEXT = "receive_sharing_intent/events-text"

class ReceiveSharingIntentPlugin : FlutterPlugin, ActivityAware, MethodCallHandler,
    EventChannel.StreamHandler, PluginRegistry.NewIntentListener {

    private lateinit var context: Context
    private var binding: ActivityPluginBinding? = null

    private var initialIntent: Intent? = null
    private var latestMedia: JSONArray? = null
    private var eventSinkMedia: EventChannel.EventSink? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        setupChannels(binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {}

    private fun setupChannels(messenger: BinaryMessenger) {
        MethodChannel(messenger, MESSAGES_CHANNEL).setMethodCallHandler(this)
        EventChannel(messenger, EVENTS_CHANNEL_MEDIA).setStreamHandler(this)
        EventChannel(messenger, EVENTS_CHANNEL_TEXT).setStreamHandler(this)
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSinkMedia = events
        latestMedia?.let { eventSinkMedia?.success(it.toString()) }
    }

    override fun onCancel(arguments: Any?) {
        eventSinkMedia = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getInitialMedia" -> {
                val intent = initialIntent
                initialIntent = null

                Log.d(TAG, "getInitialMedia: called, intent=${intent != null}")
                if (intent == null) {
                    Log.d(TAG, "getInitialMedia: no intent, returning null")
                    result.success(null)
                    return
                }

                Log.d(TAG, "getInitialMedia: processing intent on background thread")
                executor.execute {
                    try {
                        val media = getMedia(intent)
                        mainHandler.post { result.success(media?.toString()) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing initial media", e)
                        mainHandler.post {
                            result.error("PROCESSING_ERROR", e.message, null)
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

    private fun handleIntent(intent: Intent, isInitial: Boolean) {
        Log.d(TAG, "handleIntent: isInitial=$isInitial, action=${intent.action}, type=${intent.type}")
        executor.execute {
            try {
                Log.d(TAG, "handleIntent: processing on background thread")
                val media = getMedia(intent)
                Log.d(TAG, "handleIntent: got media=${media != null}")

                if (!isInitial && media != null) {
                    latestMedia = media
                    mainHandler.post {
                        eventSinkMedia?.success(media.toString())
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Intent handling failed", e)
                if (!isInitial) {
                    mainHandler.post {
                        eventSinkMedia?.error("INTENT_ERROR", e.message, null)
                    }
                }
            }
        }
    }

    private fun getMedia(intent: Intent?): JSONArray? {
        intent ?: return null

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                // If it has a type, treat as media file; otherwise treat as URL
                if (intent.type != null) {
                    val uri = intent.data
                    toJson(uri, null, intent.type)?.let { JSONArray(listOf(it)) }
                } else {
                    // It's a URL, not a media file
                    JSONArray(
                        listOf(JSONObject()
                            .put("path", intent.dataString)
                            .put("type", MediaType.URL.value))
                    )
                }
            }

            Intent.ACTION_SEND -> {
                val uri = intent.parcelable<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                toJson(uri, text, intent.type)?.let { JSONArray(listOf(it)) }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.parcelableArrayList<Uri>(Intent.EXTRA_STREAM)
                val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)

                uris?.mapIndexedNotNull { i, uri ->
                    toJson(uri, text, mimeTypes?.getOrNull(i))
                }?.let { JSONArray(it) }
            }

            Intent.ACTION_SENDTO -> {
                // Handle mailto links
                if (intent.scheme?.startsWith("mailto") == true) {
                    JSONArray(
                        listOf(JSONObject()
                            .put("path", intent.data?.schemeSpecificPart)
                            .put("type", MediaType.MAILTO.value))
                    )
                } else null
            }

            else -> null
        }
    }

    private fun toJson(uri: Uri?, text: String?, mimeType: String?): JSONObject? {
        val path = uri?.let { resolvePath(it) }
        val mType = mimeType ?: path?.let { URLConnection.guessContentTypeFromName(it) }
        val type = MediaType.fromMimeType(mType)

        val (thumbnail, duration) =
            if (path != null) generateVideoMetadata(path, type) else null to null

        return JSONObject()
            .put("path", path ?: text)
            .put("type", type.value)
            .put("mimeType", mType)
            .put("thumbnail", thumbnail)
            .put("duration", duration)
            .put("text", text)
    }

    private fun resolvePath(uri: Uri): String? {
        val direct = FileDirectory.getAbsolutePath(context, uri)
        if (direct != null) {
            if (direct.startsWith("/sdcard/") || direct.startsWith("/storage/emulated/"))
                return copyFileToCache(direct)
            return direct
        }
        return copyUriToTempFile(uri)
    }

    private fun generateVideoMetadata(path: String, type: MediaType): Pair<String?, Long?> {
        if (type != MediaType.VIDEO) return null to null

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(path)

        val duration = retriever.extractMetadata(METADATA_KEY_DURATION)?.toLongOrNull()
        val bitmap = retriever.getScaledFrameAtTime(-1, OPTION_CLOSEST_SYNC, 360, 360)
        retriever.release()

        if (bitmap == null) return null to null

        val file = File(context.cacheDir, "${File(path).name}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        return file.path to duration
    }

    private fun copyFileToCache(path: String): String? =
        try {
            val source = File(path)
            val temp = File(context.cacheDir, source.name)
            FileInputStream(source).use { input ->
                FileOutputStream(temp).use { output ->
                    input.copyTo(output)
                }
            }
            temp.path
        } catch (e: Exception) {
            Log.e(TAG, "copyFileToCache error", e)
            null
        }

    private fun copyUriToTempFile(uri: Uri): String? {
        val name = getFileName(uri) ?: "shared_${System.currentTimeMillis()}"
        val file = File(context.cacheDir, name)

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.path
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI", e)
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) return cursor.getString(index)
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    enum class MediaType(val value: String) {
        IMAGE("image"),
        VIDEO("video"),
        TEXT("text"),
        FILE("file"),
        URL("url"),
        MAILTO("mailto");

        companion object {
            fun fromMimeType(mime: String?) = when {
                mime?.startsWith("image") == true -> IMAGE
                mime?.startsWith("video") == true -> VIDEO
                mime == "text" -> TEXT
                else -> FILE
            }
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addOnNewIntentListener(this)

        binding.activity.intent?.let {
            Log.d(TAG, "onAttachedToActivity: intent action=${it.action}, type=${it.type}, data=${it.data}")
            initialIntent = it
            handleIntent(it, true)
        } ?: run {
            Log.d(TAG, "onAttachedToActivity: no intent found")
        }
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onDetachedFromActivity() {
        binding?.removeOnNewIntentListener(this)
    }

    override fun onNewIntent(intent: Intent): Boolean {
        handleIntent(intent, false)
        return true
    }

    inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(key) as? T

    inline fun <reified T : Parcelable> Intent.parcelableArrayList(key: String): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java)
        else @Suppress("DEPRECATION") getParcelableArrayListExtra(key)
}
