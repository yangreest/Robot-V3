package com.skydroid.insulatordetect.detect

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

/**
 * 配置持久化：DetectConfig 以 JSON 存入 SharedPreferences，启动时恢复
 */
class ConfigStore(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): DetectConfig {
        val json = sp.getString(KEY_CONFIG, null) ?: return DetectConfig()
        return try {
            gson.fromJson(json, DetectConfig::class.java) ?: DetectConfig()
        } catch (e: Exception) {
            DetectConfig()
        }
    }

    fun save(config: DetectConfig) {
        sp.edit().putString(KEY_CONFIG, gson.toJson(config)).apply()
    }

    companion object {
        private const val SP_NAME = "detect_config"
        private const val KEY_CONFIG = "config_json"
    }
}
