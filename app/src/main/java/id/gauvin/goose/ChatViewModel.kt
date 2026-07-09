package id.gauvin.goose

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

data class ChatMessage(val role: String, val text: String)

class ChatViewModel : ViewModel() {
    val messages = mutableStateListOf<ChatMessage>()
    val status = mutableStateOf("not connected")

    private val main = Handler(Looper.getMainLooper())
    private var client: AcpClient? = null
    private var streaming = false

    fun connect(host: String, port: String, key: String) {
        client?.close()
        val url = "ws://$host:$port/acp"
        status.value = "connecting to $url"
        client = AcpClient(url, key) { ev -> main.post { onEvent(ev) } }.also { it.connect() }
    }

    fun send(text: String) {
        messages.add(ChatMessage("user", text))
        streaming = false
        client?.sendPrompt(text)
    }

    private fun onEvent(ev: AcpEvent) {
        when (ev) {
            is AcpEvent.Status -> status.value = ev.text
            is AcpEvent.Error -> { messages.add(ChatMessage("error", ev.text)); streaming = false }
            is AcpEvent.ToolCall -> { messages.add(ChatMessage("tool", ev.title)); streaming = false }
            is AcpEvent.TurnDone -> streaming = false
            is AcpEvent.AgentChunk -> appendAgent(ev.text)
        }
    }

    private fun appendAgent(chunk: String) {
        val last = messages.lastOrNull()
        if (streaming && last != null && last.role == "assistant") {
            messages[messages.lastIndex] = last.copy(text = last.text + chunk)
        } else {
            streaming = true
            messages.add(ChatMessage("assistant", chunk))
        }
    }

    override fun onCleared() { client?.close() }
}
