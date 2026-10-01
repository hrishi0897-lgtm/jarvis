package com.jarvis.app

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val KEY_MODEL_NAME = "gemini_model_name"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }

    fun getApiKey(): String {
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getModelName(): String {
        return prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setModelName(modelName: String) {
        prefs.edit().putString(KEY_MODEL_NAME, modelName.trim()).apply()
    }

    fun getAllowedPackages(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        val current = getAllowedPackages().toMutableSet()
        if (allowed) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, current).apply()
    }
}
