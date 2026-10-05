package com.example.microgridsystem.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import retrofit2.Response

object ApiErrorUtils {

    /**
     * Parses error response JSON body to extract "message" or "Message".
     * Falls back to default message if parsing fails.
     */
    fun parseErrorMessage(response: Response<*>?, defaultMessage: String = "An unexpected error occurred."): String {
        if (response == null) return defaultMessage
        return try {
            val errorBody = response.errorBody()?.string()
            if (!errorBody.isNullOrBlank()) {
                val jsonObject = Gson().fromJson(errorBody, JsonObject::class.java)
                when {
                    jsonObject.has("message") -> jsonObject.get("message").asString
                    jsonObject.has("Message") -> jsonObject.get("Message").asString
                    else -> defaultMessage
                }
            } else {
                defaultMessage
            }
        } catch (e: Exception) {
            defaultMessage
        }
    }
}
