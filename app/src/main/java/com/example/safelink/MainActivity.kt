package com.example.safelink // Make sure this matches your package name!

import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Arrays

class MainActivity : AppCompatActivity() {

    private lateinit var tflite: Interpreter
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var latencyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. UI Bindings
        val inputMessage = findViewById<EditText>(R.id.inputMessage)
        val btnScan = findViewById<Button>(R.id.btnScan)
        statusText = findViewById(R.id.statusText)
        resultText = findViewById(R.id.resultText)
        latencyText = findViewById(R.id.latencyText)

        // 2. Load Model
        try {
            tflite = Interpreter(loadModelFile())
            statusText.text = "Model Loaded Successfully"
            statusText.setTextColor(getColor(android.R.color.holo_green_dark))
        } catch (e: Exception) {
            statusText.text = "Error Loading Model!"
            statusText.setTextColor(getColor(android.R.color.holo_red_dark))
            e.printStackTrace()
        }

        // 3. Scan Button Listener
        btnScan.setOnClickListener {
            val text = inputMessage.text.toString()
            if (text.isNotEmpty()) {
                runInference(text)
            }
        }
    }

    private fun runInference(text: String) {
        // --- A. Pre-Processing (Tokenizer Mock) ---
        // NOTE: In a real app, you need a full Tokenizer implementation here.
        // For this "Skeleton Test", we will just pad with Zeros to check LATENCY.
        // We are measuring SPEED, not accuracy, for the first mobile run.

        val maxLen = 64
        val inputIds = IntArray(maxLen) { 0 } // Dummy tokens
        val attentionMask = IntArray(maxLen) { 1 } // Dummy mask
        val urlFeatures = floatArrayOf(0f, 0f, 0f) // Dummy URL feats

        // Reshape for TFLite [1, 64]
        val inputIdsBatch = Array(1) { inputIds }
        val maskBatch = Array(1) { attentionMask }
        val urlBatch = Array(1) { urlFeatures }

        // Prepare Inputs Map (Order depends on your specific TFLite export)
        // Check your Python script output: Index 0=Mask, 1=URL, 2=IDs (Example)
        val inputs = mapOf(
            0 to maskBatch,
            1 to urlBatch,
            2 to inputIdsBatch
        )

        // Prepare Output Buffer [1, 1]
        val outputBuffer = Array(1) { FloatArray(1) }
        val outputs = mapOf(0 to outputBuffer)

        // --- B. Inference (The Critical Test) ---
        val startTime = SystemClock.elapsedRealtime()

        tflite.runForMultipleInputsOutputs(arrayOf(maskBatch, urlBatch, inputIdsBatch), outputs)

        val endTime = SystemClock.elapsedRealtime()
        val latency = endTime - startTime

        // --- C. Display Results ---
        val probability = outputBuffer[0][0]
        val isPhishing = probability > 0.5

        latencyText.text = "Latency: ${latency}ms"

        if (isPhishing) {
            resultText.text = "PHISHING DETECTED (${String.format("%.2f", probability)})"
            resultText.setTextColor(getColor(android.R.color.holo_red_dark))
        } else {
            resultText.text = "Safe Message (${String.format("%.2f", probability)})"
            resultText.setTextColor(getColor(android.R.color.holo_green_dark))
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = assets.openFd("safelink_model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}