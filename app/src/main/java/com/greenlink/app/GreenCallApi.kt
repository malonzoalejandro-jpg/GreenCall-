package com.greenlink.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GreenCallApi {
    const val BASE_URL = "https://greencall-api-production.up.railway.app"

    fun request(method: String, path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            token?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val result = if (raw.isBlank()) JSONObject() else JSONObject(raw)
            if (status !in 200..299) throw IllegalStateException(result.optString("error").ifBlank { "Request failed ($status)." })
            return result
        } finally {
            connection.disconnect()
        }
    }

    fun register(displayName: String, username: String, password: String) =
        request("POST", "/v1/auth/register", body = JSONObject()
            .put("displayName", displayName).put("username", username).put("password", password))

    fun login(username: String, password: String) =
        request("POST", "/v1/auth/login", body = JSONObject()
            .put("username", username).put("password", password))

    fun searchUsers(token: String, displayName: String): JSONArray =
        request("GET", "/v1/users/search?name=" + java.net.URLEncoder.encode(displayName, "UTF-8"), token)
            .optJSONArray("users") ?: JSONArray()

    fun addFriend(token: String, id: String) =
        request("POST", "/v1/friends/$id", token, JSONObject())

    fun getFriends(token: String): JSONArray =
        request("GET", "/v1/friends", token).optJSONArray("friends") ?: JSONArray()
    fun createDirect(token: String, userId: String): String =
        request("POST", "/v1/conversations/direct", token, JSONObject().put("userId", userId))
            .getString("conversationId")

    fun getMessages(token: String, conversationId: String): JSONArray =
        request("GET", "/v1/conversations/$conversationId/messages", token)
            .optJSONArray("messages") ?: JSONArray()

    fun sendMessage(token: String, conversationId: String, body: String): JSONObject =
        request("POST", "/v1/conversations/$conversationId/messages", token,
            JSONObject().put("body", body)).getJSONObject("message")

}
