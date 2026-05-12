package com.example.safelink

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class SmsSecurityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

            // 1. Tell Android we need more time to process this in the background!
            val pendingResult = goAsync()

            // 2. Launch a background thread so we don't freeze the phone
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Create the notification channel (Required for Android 8.0+)
                    createNotificationChannel(context)

                    for (sms in messages) {
                        val sender = sms.displayOriginatingAddress ?: "Unknown"
                        val messageBody = sms.displayMessageBody ?: ""

                        // Run Inference
                        val isThreat = scanMessageInBg(context, messageBody)

                        // If it's a threat, trigger the notification
                        if (isThreat) {
                            sendSecurityNotification(context, sender)
                        }
                    }
                } finally {
                    // 3. Tell Android we are done, it can put the receiver back to sleep
                    pendingResult.finish()
                }
            }
        }
    }

    private fun scanMessageInBg(context: Context, text: String): Boolean {
        var tflite: Interpreter? = null
        try {
            // 1. Setup Interpreter with Multi-Threading
            val options = Interpreter.Options().apply { numThreads = 4 }
            tflite = Interpreter(loadModelFile(context), options)

            // 2. Load Tokenizer
            val tokenizer = DistilBertTokenizer(context)

            // --- 3. APPLY FULL PREPROCESSING PIPELINE ---
            var cleanText = text.lowercase()

            val zeroWidthRegex = Regex("[\u200B-\u200D\uFEFF]")
            val hasZeroWidth = if (zeroWidthRegex.containsMatchIn(text)) 1f else 0f
            cleanText = cleanText.replace(zeroWidthRegex, "")

            val shorteners = listOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly")
            val isShortened = if (shorteners.any { text.contains(it, ignoreCase = true) }) 1f else 0f

            val homoglyphs = mapOf(
                "а" to "a", "ϲ" to "c", "е" to "e", "о" to "o", "р" to "p", "х" to "x", "у" to "y",
                "с" to "c", "в" to "v", "к" to "k", "н" to "n", "т" to "t", "м" to "m"
            )
            for ((homoglyph, standard) in homoglyphs) {
                cleanText = cleanText.replace(homoglyph, standard)
            }

            val hasUrl = if (text.contains("http")) 1f else 0f
            val lenUrl = text.split(" ").count { it.contains("http") }.toFloat()

            // 4. Tokenize and Build Arrays
            val (inputIds, attentionMask) = tokenizer.tokenize(cleanText)
            val urlFeatures = floatArrayOf(hasUrl, lenUrl, hasZeroWidth, isShortened)

            val inputIdsBatch = Array(1) { inputIds }
            val maskBatch = Array(1) { attentionMask }
            val urlBatch = Array(1) { urlFeatures }

            val outputBuffer = Array(1) { FloatArray(1) }
            val outputs = mapOf(0 to outputBuffer)

            // 5. Run Model
            tflite.runForMultipleInputsOutputs(arrayOf(maskBatch, urlBatch, inputIdsBatch), outputs)

            val probability = outputBuffer[0][0]

            return probability > 0.5

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            // ALWAYS close the model to prevent memory leaks in the background!
            tflite?.close()
        }
    }

    private fun createNotificationChannel(context: Context) {
        // Android 8.0+ requires a Notification Channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SafeLink Alerts"
            val descriptionText = "Notifications for detected smishing threats"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("SAFELINK_CHANNEL", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendSecurityNotification(context: Context, sender: String) {
        val notification = NotificationCompat.Builder(context, "SAFELINK_CHANNEL")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("⚠️ Smishing Threat Detected!")
            .setContentText("SafeLink blocked a malicious text from $sender.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("safelink_model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }
}