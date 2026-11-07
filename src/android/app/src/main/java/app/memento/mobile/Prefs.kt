package app.memento.mobile

import android.content.Context
import org.json.JSONObject

/** app_settings_kv table + tauri store plugin backing. */
class Prefs(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("memento_store", Context.MODE_PRIVATE)
    fun get(key: String): String? = sp.getString(key, null)
    fun set(key: String, value: String) = sp.edit().putString(key, value).apply()
    fun setSync(key: String, value: String): Boolean = sp.edit().putString(key, value).commit()
    fun delete(key: String) = sp.edit().remove(key).apply()
    fun keys(): List<String> = sp.all.keys.toList()
    fun snapshot(): JSONObject { val o = JSONObject(); sp.all.forEach { (k, v) -> o.put(k, v.toString()) }; return o }
}
