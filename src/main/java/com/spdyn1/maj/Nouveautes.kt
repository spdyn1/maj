package com.spdyn1.maj

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * « Nouveautés » partagées par les applis : l'historique des versions publiées.
 * Le site de l'appli publie <site>nouveautes.json = [{"versionCode", "versionName", "date" (AAAA-MM-JJ), "notes"}, …], la plus récente d'abord
 * (ajouté à chaque publication par le workflow partagé). [lues] rend la dernière copie gardée (utilisable hors ligne) ;
 * [charger] la rafraîchit depuis le site. [nonVu] / [marquerVu] : la version installée a-t-elle déjà été montrée à l'utilisateur ?
 */
object Nouveautes {
    data class Entree(val code: Long, val nom: String, val date: String, val notes: String)

    private val principal = Handler(Looper.getMainLooper())
    private fun prefs(c: Context) = c.getSharedPreferences("maj", Context.MODE_PRIVATE)
    private fun installee(c: Context) = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    private fun lire(json: String): List<Entree> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { val o: JSONObject = a.getJSONObject(it)
            Entree(o.getLong("versionCode"), o.optString("versionName"), o.optString("date"), o.optString("notes")) }
    }.getOrDefault(emptyList())

    /** la dernière copie reçue du site (vide si jamais reçue) */
    fun lues(context: Context): List<Entree> = lire(prefs(context).getString("nouveautes", "")!!.ifBlank { "[]" })

    /** relit le site en arrière-plan ; [fin] reçoit la liste à jour sur le fil principal (la copie gardée si le site est injoignable) */
    fun charger(context: Context, fin: (List<Entree>) -> Unit) {
        val app = context.applicationContext
        Thread {
            val texte = runCatching {
                val site = app.getString(R.string.maj_site)
                val c = URL(site + "nouveautes.json").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000; c.useCaches = false
                c.inputStream.use { it.reader().readText() }
            }.getOrNull()
            val liste = texte?.let(::lire)?.takeIf { it.isNotEmpty() }
            if (liste != null) prefs(app).edit().putString("nouveautes", texte).apply()
            principal.post { fin(liste ?: lues(app)) }
        }.start()
    }

    /** vrai si la version installée n'a pas encore été montrée dans les nouveautés */
    fun nonVu(context: Context): Boolean = installee(context) > prefs(context).getLong("nouveautes_vue", 0)

    fun marquerVu(context: Context) { prefs(context).edit().putLong("nouveautes_vue", installee(context)).apply() }
}
