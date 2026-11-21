package com.spdyn1.maj

import org.json.JSONObject
import java.util.Locale

/** Notes de version dans la langue du téléphone : « notes » est en français, « notes_en » (facultatif) sert à toute autre langue. */
internal object Notes {
    fun choisir(o: JSONObject): String {
        val fr = o.optString("notes")
        return if (Locale.getDefault().language == "fr") fr else o.optString("notes_en").ifBlank { fr }
    }
}
