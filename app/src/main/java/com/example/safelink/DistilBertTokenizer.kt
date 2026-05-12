package com.example.safelink

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class DistilBertTokenizer(context: Context) {
    private val vocab = mutableMapOf<String, Int>()
    private val MAX_LEN = 64 // Fixed sequence length based on training

    init {
        // Load vocab.txt from the assets folder
        val inputStream = context.assets.open("vocab.txt")
        val reader = BufferedReader(InputStreamReader(inputStream))
        var index = 0
        reader.forEachLine { line ->
            vocab[line] = index++
        }
        reader.close()
    }

    fun tokenize(text: String): Pair<IntArray, IntArray> {
        val inputIds = IntArray(MAX_LEN) { 0 } // Defaults to [PAD] = 0
        val attentionMask = IntArray(MAX_LEN) { 0 }

        val tokens = mutableListOf<Int>()
        tokens.add(101) // [CLS] token

        // Basic lowercase and punctuation split
        val words = text.lowercase().replace(Regex("([.,!?()])"), " $1 ")
            .split("\\s+".toRegex()).filter { it.isNotEmpty() }

        // WordPiece subword tokenization
        for (word in words) {
            var start = 0
            while (start < word.length) {
                var end = word.length
                var matchId = -1

                while (start < end) {
                    val subStr = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                    if (vocab.containsKey(subStr)) {
                        matchId = vocab[subStr]!!
                        break
                    }
                    end -= 1
                }

                if (matchId == -1) {
                    tokens.add(100) // [UNK] token
                    break
                } else {
                    tokens.add(matchId)
                    start = end
                }
            }
        }

        tokens.add(102) // [SEP] token

        // Populate arrays up to MAX_LEN
        for (i in tokens.indices) {
            if (i >= MAX_LEN) break
            inputIds[i] = tokens[i]
            attentionMask[i] = 1 // 1 for real tokens, 0 for padding
        }

        return Pair(inputIds, attentionMask)
    }
}