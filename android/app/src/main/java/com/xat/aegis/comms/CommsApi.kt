package com.xat.aegis.comms

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A relay call that did not succeed, with the relay's own message when it gave one. */
class CommsException(message: String, val code: Int = 0) : IOException(message)

/**
 * HTTP client for the comms Worker. Every call is blocking and belongs on an IO
 * dispatcher. Responses are the JSON shapes documented in comms-worker/README.md.
 */
class CommsApi(private val config: CommsConfig) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Enrolled(val deviceId: String, val token: String, val number: String?)

    data class Status(val number: String?, val deviceId: String, val pushRegistered: Boolean, val devices: Int)

    data class Page(val messages: List<SmsMessage>, val next: Long, val more: Boolean)

    data class Updates(val messages: List<SmsMessage>, val now: Long)

    /** Pairs this phone: trades the enrollment secret for a bearer token. */
    fun enroll(workerUrl: String, secret: String, name: String): Enrolled {
        val body = JSONObject().put("secret", secret).put("name", name)
        val json = call(Request.Builder().url("${workerUrl.trimEnd('/')}/api/enroll").post(body.toBody()), auth = false)
        return Enrolled(
            deviceId = json.getString("deviceId"),
            token = json.getString("token"),
            number = json.optString("number").takeIf { it.isNotEmpty() && !json.isNull("number") }
        )
    }

    fun status(): Status {
        val json = call(Request.Builder().url(url("/api/status")).get())
        return Status(
            number = json.optString("number").takeIf { it.isNotEmpty() && !json.isNull("number") },
            deviceId = json.getString("deviceId"),
            pushRegistered = json.optBoolean("pushRegistered", false),
            devices = json.optJSONArray("devices")?.length() ?: 0
        )
    }

    fun registerPush(token: String?) {
        val body = JSONObject().put("token", token ?: JSONObject.NULL)
        call(Request.Builder().url(url("/api/device/fcm")).put(body.toBody()))
    }

    fun unpair() {
        call(Request.Builder().url(url("/api/device")).delete())
    }

    fun messagesAfter(seq: Long): Page {
        val json = call(Request.Builder().url(url("/api/messages?after=$seq")).get())
        return Page(
            messages = parseMessages(json.getJSONArray("messages")),
            next = json.getLong("next"),
            more = json.optBoolean("more", false)
        )
    }

    fun updatesSince(ts: Long): Updates {
        val json = call(Request.Builder().url(url("/api/messages/updates?since=$ts")).get())
        return Updates(parseMessages(json.getJSONArray("messages")), json.getLong("now"))
    }

    fun send(to: String, text: String): SmsMessage {
        val body = JSONObject().put("to", to).put("body", text)
        val json = call(Request.Builder().url(url("/api/messages")).post(body.toBody()))
        return parseMessage(json.getJSONObject("message"))
    }

    fun refresh(id: String): SmsMessage? {
        val json = call(Request.Builder().url(url("/api/messages/$id/refresh")).post(JSONObject().toBody()))
        return json.optJSONObject("message")?.let { parseMessage(it) }
    }

    private fun url(path: String): String {
        val base = config.workerUrl ?: throw CommsException("Not paired with a relay")
        return base + path
    }

    private fun call(builder: Request.Builder, auth: Boolean = true): JSONObject {
        if (auth) {
            val token = config.token() ?: throw CommsException("The relay token could not be read; pair again")
            builder.header("Authorization", "Bearer $token")
        }
        builder.header("Accept", "application/json")
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw CommsException("Could not reach the relay: ${e.message ?: e.javaClass.simpleName}")
        }
        response.use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!res.isSuccessful) {
                val reason = json?.optString("error")?.takeIf { it.isNotEmpty() } ?: "HTTP ${res.code}"
                throw CommsException(reason, res.code)
            }
            return json ?: throw CommsException("The relay returned something that is not JSON")
        }
    }

    private fun JSONObject.toBody() = toString().toRequestBody(JSON)

    private fun parseMessages(arr: JSONArray): List<SmsMessage> =
        (0 until arr.length()).map { parseMessage(arr.getJSONObject(it)) }

    private fun parseMessage(o: JSONObject): SmsMessage {
        val media = ArrayList<MediaItem>()
        o.optJSONArray("media")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                media += MediaItem(m.getString("url"), m.optString("contentType", "application/octet-stream"))
            }
        }
        return SmsMessage(
            id = o.getString("id"),
            seq = o.getLong("seq"),
            direction = if (o.getString("direction") == "out") Direction.OUT else Direction.IN,
            peer = o.getString("peer"),
            body = o.optString("body", ""),
            media = media,
            status = o.optString("status", "unknown"),
            error = if (o.isNull("error")) null else o.optString("error"),
            ts = o.getLong("ts"),
            updated = o.optLong("updated", o.getLong("ts")),
            read = false
        )
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
