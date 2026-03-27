package com.mg4.control.profile

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.model.DrivingProfile

class ProfileManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "mg4_profiles"
        private const val KEY_PROFILES = "profiles_json"
        private const val KEY_DEFAULT_ID = "default_profile_id"
        const val MAX_PROFILES = 5
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    fun getAll(): List<DrivingProfile> {
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DrivingProfile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun save(profile: DrivingProfile): Boolean {
        val list = getAll().toMutableList()
        val existing = list.indexOfFirst { it.id == profile.id }
        if (existing >= 0) {
            list[existing] = profile
        } else {
            if (list.size >= MAX_PROFILES) return false
            list.add(profile)
        }
        persist(list)
        return true
    }

    fun delete(profileId: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.id == profileId }
        persist(list)
        // Clear default if it was this profile
        if (prefs.getString(KEY_DEFAULT_ID, null) == profileId) {
            prefs.edit().remove(KEY_DEFAULT_ID).apply()
        }
    }

    fun setDefault(profileId: String) {
        prefs.edit().putString(KEY_DEFAULT_ID, profileId).apply()
    }

    fun clearDefault() {
        prefs.edit().remove(KEY_DEFAULT_ID).apply()
    }

    fun getDefaultProfile(): DrivingProfile? {
        val defaultId = prefs.getString(KEY_DEFAULT_ID, null) ?: return null
        return getAll().firstOrNull { it.id == defaultId }
    }

    fun getDefaultId(): String? = prefs.getString(KEY_DEFAULT_ID, null)

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private fun persist(list: List<DrivingProfile>) {
        prefs.edit().putString(KEY_PROFILES, gson.toJson(list)).apply()
    }
}
