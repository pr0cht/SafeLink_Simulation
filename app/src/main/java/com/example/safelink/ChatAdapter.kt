package com.example.safelink

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

// 1. Updated Data Structure
data class ChatMessage(
    val text: String,
    var isScanned: Boolean = false,
    var isThreat: Boolean = false,
    var threatPercentage: Int = 0,
    var latencyMs: Long = 0
)

// 2. The Adapter
class ChatAdapter(private val messages: MutableList<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val securityStatusText: TextView = view.findViewById(R.id.securityStatusText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_bubble, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        holder.messageText.text = msg.text

        if (!msg.isScanned) {
            holder.securityStatusText.text = "Scanning..."
            holder.securityStatusText.setTextColor(Color.GRAY)
        } else {
            if (msg.isThreat) {
                holder.securityStatusText.text = "⚠️ THREAT DETECTED (${msg.threatPercentage}% match) - ${msg.latencyMs}ms"
                holder.securityStatusText.setTextColor(Color.RED)
            } else {
                holder.securityStatusText.text = "✓ SAFE (${msg.threatPercentage}% threat) - ${msg.latencyMs}ms"
                holder.securityStatusText.setTextColor(Color.parseColor("#006400")) // Dark Green
            }
        }
    }

    override fun getItemCount() = messages.size

    // Updated based on Gemini's suggestion
    fun updateMessageResult(position: Int, isPhishing: Boolean, threatPercentage: Int, latencyMs: Long) {
        if (position >= 0 && position < messages.size) {
            val message = messages[position]
            message.isScanned = true
            message.isThreat = isPhishing
            message.threatPercentage = threatPercentage
            message.latencyMs = latencyMs
            notifyItemChanged(position)
        }
    }
}