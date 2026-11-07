package com.spdyn1.maj

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Avis de nouvelle version, partagé par les applis (module « maj », sans Compose).
 * Le site de l'appli (ressource R.string.maj_site, redéfinie par l'appli) publie <site>version.json = {"versionCode", "versionName", "notes", "apk", "sha256"}.
 * [verifier] le lit au plus toutes les 3 heures (ou de force) ; plus récent que l'appli installée et pas écarté par
 * « Plus tard » → [disponible]. [mettreAJour] télécharge l'APK dans le cache, vérifie son empreinte, puis ouvre
 * l'installeur d'Android (qui demande la première fois d'autoriser l'appli à installer des applis).
 * [ecouteur] est appelé sur le fil principal à chaque changement de [disponible] ou d'[etat].
 */
object MiseAJour {
    private const val ECART_MS = 3 * 3600_000L

    data class Info(val code: Long, val nom: String, val notes: String, val apk: String, val sha256: String?)

    sealed interface Etat {
        data object Pret : Etat
        data class Telechargement(val progres: Float?) : Etat
        data object Autorisation : Etat                 // réglage « installer des applis » ouvert
        data object Installation : Etat                 // installeur d'Android ouvert
        data class Erreur(val message: String) : Etat
    }

    var disponible: Info? = null; private set
    var etat: Etat = Etat.Pret; private set
    var ecouteur: (() -> Unit)? = null

    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var enCours = false

    private fun site(c: Context) = c.getString(R.string.maj_site).also { require(it.endsWith("/")) { "maj_site non défini" } }
    private fun prefs(c: Context) = c.getSharedPreferences("maj", Context.MODE_PRIVATE)
    private fun fichier(c: Context) = File(File(c.cacheDir, "maj"), "maj.apk")
    private fun installee(c: Context) = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    private fun changer(d: Info? = disponible, e: Etat = etat) = principal.post { disponible = d; etat = e; ecouteur?.invoke() }

    /**
     * À appeler au retour au premier plan. Avec [resultat] (bouton « Rechercher une mise à jour ») : vérification immédiate,
     * une version écartée par « Plus tard » est de nouveau proposée, et [resultat] reçoit sur le fil principal
     * true (nouvelle version), false (déjà à jour) ou null (pas de réseau, site injoignable).
     */
    fun verifier(context: Context, force: Boolean = false, resultat: ((Boolean?) -> Unit)? = null) {
        val app = context.applicationContext
        reprendre(app)
        if (etat != Etat.Pret) { resultat?.let { r -> principal.post { r(disponible != null) } }; return }
        if (enCours) return
        val p = prefs(app)
        val maintenant = System.currentTimeMillis()
        if (resultat != null) p.edit().remove("ecartee").apply()
        else if (!force && maintenant - p.getLong("vu", 0) < ECART_MS) return
        enCours = true
        Thread {
            try {
                val site = site(app)
                val c = URL(site + "version.json").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000; c.useCaches = false
                val o = c.inputStream.use { JSONObject(it.reader().readText()) }
                val info = Info(o.getLong("versionCode"), o.optString("versionName"), o.optString("notes"),
                    URL(URL(site), o.optString("apk", "maj.apk")).toString(), o.optString("sha256").ifBlank { null }?.lowercase())
                p.edit().putLong("vu", maintenant).apply()
                val nouvelle = info.code > installee(app) && info.code > p.getLong("ecartee", 0)
                if (nouvelle) changer(d = info)
                else { changer(d = null); File(app.cacheDir, "maj").deleteRecursively() }
                resultat?.let { r -> principal.post { r(nouvelle) } }
            } catch (_: Exception) {
                // hors ligne : on réessaiera au prochain retour
                resultat?.let { r -> principal.post { r(null) } }
            } finally { enCours = false }
        }.start()
    }

    /** « Plus tard » : cette version ne sera plus annoncée (la suivante, si) */
    fun ecarter(context: Context) {
        val info = disponible ?: return
        prefs(context).edit().putLong("ecartee", info.code).apply()
        changer(d = null, e = Etat.Pret)
    }

    /** « Mettre à jour » : télécharge (sauf si l'APK est déjà là et intact), puis installe */
    fun mettreAJour(context: Context) {
        val info = disponible ?: return
        if (etat is Etat.Telechargement) return
        val app = context.applicationContext
        Thread {
            val f = fichier(app)
            try {
                if (!(f.exists() && intact(f, info.sha256))) {
                    changer(e = Etat.Telechargement(null))
                    telecharger(info.apk, f)
                    if (!intact(f, info.sha256)) {
                        f.delete()
                        changer(e = Etat.Erreur("Le fichier reçu n'est pas le bon (le site vient peut-être d'être mis à jour). Réessaie dans quelques minutes."))
                        return@Thread
                    }
                }
                principal.post { installer(app) }
            } catch (_: Exception) {
                f.delete()
                changer(e = Etat.Erreur("Téléchargement impossible. Vérifie la connexion Internet et réessaie."))
            }
        }.start()
    }

    /** au retour dans l'appli : si l'on vient d'autoriser l'installation, on continue */
    private fun reprendre(context: Context) {
        if (etat == Etat.Autorisation && context.packageManager.canRequestPackageInstalls()) principal.post { installer(context) }
    }

    private fun installer(context: Context) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            changer(e = Etat.Autorisation)
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.maj", fichier(context))
        changer(e = Etat.Installation)
        // installeur dans sa propre tâche (sinon son bouton « Ouvrir » ramène la tâche arrêtée par la mise à jour) ;
        // INSTALL_PACKAGE et non VIEW, pour aller droit à l'installeur
        @Suppress("DEPRECATION")
        context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun telecharger(url: String, f: File) {
        f.parentFile!!.mkdirs()
        val tmp = File(f.path + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.useCaches = false
        val total = c.contentLengthLong
        var dernier = 0L
        c.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var lu = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); lu += n
                    val t = System.currentTimeMillis()
                    if (total > 0 && t - dernier > 100) { dernier = t; changer(e = Etat.Telechargement(lu.toFloat() / total)) }
                }
            }
        }
        if (!tmp.renameTo(f)) throw IllegalStateException("renommage")
    }

    private fun intact(f: File, sha256: String?): Boolean {
        if (sha256 == null) return f.length() > 0
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) } == sha256
    }
}
