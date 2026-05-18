package com.example.safelink

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var chatRecyclerView: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private val messageList = mutableListOf<ChatMessage>()

    // AI
    private lateinit var tflite: Interpreter
    private lateinit var tokenizer: DistilBertTokenizer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ??????????????? might retry
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter(messageList)
        chatRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        chatRecyclerView.adapter = chatAdapter

        val btnSend = findViewById<Button>(R.id.btnSend)
        val inputMessage = findViewById<EditText>(R.id.inputMessage)

        // Initialize AI Model and Tokenizer
        try {
            val options = Interpreter.Options().apply {
                numThreads = 4 // Hardware acceleration
            }
            tflite = Interpreter(loadModelFile(), options)
            tokenizer = DistilBertTokenizer(this)

            //
            runInference("warmup", -1)

        } catch (e: Exception) {
            // Using a popup Toast message instead of the old statusText
            Toast.makeText(this, "Error Loading AI Resources!", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }

        // The Send Button Logic
        btnSend.setOnClickListener {
            val text = inputMessage.text.toString()
            if (text.isNotEmpty()) {
                // Clear the input box
                inputMessage.text.clear()

                // Add the message to the screen as "Scanning..."
                val newMessage = ChatMessage(text)
                messageList.add(newMessage)

                // Get the exact row number (position) of this new message
                val position = messageList.size - 1
                chatAdapter.notifyItemInserted(position)
                chatRecyclerView.scrollToPosition(position)

                // Run the AI scan!
                runInference(text, position)
            }
        }
    }

    // Notice we added 'position: Int' to the parameters!
    private fun runInference(text: String, position: Int) {
        if (!::tflite.isInitialized || !::tokenizer.isInitialized) {
            Toast.makeText(this, "AI is not loaded yet!", Toast.LENGTH_SHORT).show()
            return
        }

        //
        var cleanText = text.lowercase()

        val zeroWidthRegex = Regex("[\u200B-\u200D\uFEFF]")
        val hasZeroWidth = if (zeroWidthRegex.containsMatchIn(text)) 1f else 0f
        cleanText = cleanText.replace(zeroWidthRegex, "")

        val shorteners = listOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly")
        val isShortened = if (shorteners.any { text.contains(it, ignoreCase = true) }) 1f else 0f

        val homoglyphs = mapOf(
            "а" to "a", "ϲ" to "c", "е" to "e", "о" to "o", "р" to "p", "х" to "x", "у" to "y",
            "с" to "c", "в" to "v", "к" to "k", "н" to "n", "т" to "t", "м" to "m",
            "α" to "a", "ν" to "n", "ρ" to "p"
        )
        for ((homoglyph, standard) in homoglyphs) {
            cleanText = cleanText.replace(homoglyph, standard)
        }

        val digitRegex = Regex("\\b\\d{4,}\\b")
        cleanText = cleanText.replace(digitRegex, "0000")

        val hasUrl = if (text.contains("http")) 1f else 0f
        val lenUrl = text.split(" ").count { it.contains("http") }.toFloat()

        // --- B. Tokenization and Array Building ---
        val (inputIds, attentionMask) = tokenizer.tokenize(cleanText)
        val urlFeatures = floatArrayOf(hasUrl, lenUrl, hasZeroWidth, isShortened)

        val inputIdsBatch = Array(1) { inputIds }
        val maskBatch = Array(1) { attentionMask }
        val urlBatch = Array(1) { urlFeatures }

        val outputBuffer = Array(1) { FloatArray(1) }
        val outputs = mapOf(0 to outputBuffer)

        //
        val startTimeNano = System.nanoTime()

        tflite.runForMultipleInputsOutputs(arrayOf(maskBatch, urlBatch, inputIdsBatch), outputs)

        val endTimeNano = System.nanoTime()

        // Convert nanoseconds back to milliseconds so it reads nicely on the UI
        val inferenceLatencyMs = (endTimeNano - startTimeNano) / 1_000_000

        val probability = outputBuffer[0][0]
        val isPhishing = probability > 0.5
        
        // NEW: Convert the decimal to a clean whole number (e.g., 0.124 -> 12)
        val threatPercentage = (probability * 100).toInt() 

        // --- D. Display Results on the Chat Bubble ---
        if (position >= 0) {
            // Pass the formatted threatPercentage instead of the raw probability float!
            chatAdapter.updateMessageResult(position, isPhishing, threatPercentage, inferenceLatencyMs)
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd("safelink_model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }
}