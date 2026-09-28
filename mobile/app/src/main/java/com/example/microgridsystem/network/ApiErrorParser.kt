package com.example.microgridsystem.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import retrofit2.Response

object ApiErrorParser {
    fun parseError(response: Response<*>): String {
        val code = response.code()
        if (code == 401) {
            return "Session expired or not authorized (401). Please check your credentials."
        }
        if (code == 403) {
            return "Access forbidden (403). You are not authorized to perform this operation."
        }
        
        try {
            val errorBody = response.errorBody()?.string()
            if (!errorBody.isNullOrBlank()) {
                val json = JsonParser.parseString(errorBody)
                if (json.isJsonObject) {
                    val obj: JsonObject = json.asJsonObject
                    if (obj.has("message") && !obj.get("message").isJsonNull) {
                        return obj.get("message").asString
                    }
                    if (obj.has("reason") && !obj.get("reason").isJsonNull) {
                        return obj.get("reason").asString
                    }
                    if (obj.has("title") && !obj.get("title").isJsonNull) {
                        return obj.get("title").asString
                    }
                }
                return errorBody
            }
        } catch (e: Exception) {
            // fallback
        }
        
        return "Request failed with HTTP status $code"
    }
}
