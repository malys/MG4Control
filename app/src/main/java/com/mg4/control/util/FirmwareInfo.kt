package com.mg4.control.util

/**
 * Détecte la génération de firmware à partir de ro.build.mt2712.version.
 *
 * SWI133 : "SWI133-29176-1300R32" — ADAS via getMixProperty(0x32), 5 modes, 2 alertes
 * SWI68  : "SWI68-xxxxx-xxxxx"   — ADAS via getIntProperty(0x83a), 3 modes, 1 alerte
 */
object FirmwareInfo {

    enum class Gen { SWI133, SWI68, UNKNOWN }

    @Volatile private var cached: Gen? = null

    fun getGeneration(): Gen {
        cached?.let { return it }
        val version = readProp("ro.build.mt2712.version") ?: readProp("ro.build.version.incremental")
        val gen = when {
            version == null             -> Gen.UNKNOWN
            version.startsWith("SWI133") -> Gen.SWI133
            version.startsWith("SWI68")  -> Gen.SWI68
            else                         -> Gen.UNKNOWN
        }
        cached = gen
        return gen
    }

    private fun readProp(key: String): String? = try {
        val sp  = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() && it != "0" }
    } catch (_: Exception) { null }
}
