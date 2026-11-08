# maj — avis de mise à jour des applis spdyn1

Module Android (sans Compose) partagé par Cairn, Bascules et Carnet, inclus comme sous-module git.
Le site publie `<site>/version.json` = `{"versionCode", "versionName", "notes", "apk", "sha256"}`.

Intégration :
1. `git submodule add https://github.com/spdyn1/maj.git maj`
2. `settings.gradle.kts` : `include(":maj")` ; racine : plugin `com.android.library` (apply false) ;
   appli : `implementation(project(":maj"))`.
3. `res/values` de l'appli : `<string name="maj_site" translatable="false">https://spdyn1.github.io/<appli>/</string>`.
4. L'appli affiche elle-même l'avis : `MiseAJour.verifier(ctx)` (bouton « Rechercher une mise à jour » :
   `verifier(ctx, resultat = { r -> … })`, r = true nouvelle / false à jour / null échec), `disponible`, `etat`, `ecouteur`,
   `mettreAJour(ctx)`, `ecarter(ctx)`. APK de l'appli reçu par VIEW/SEND : `installerRecu(ctx, flux)` (hors fil principal ;
   null = installeur ouvert, sinon message). Permissions et FileProvider viennent du manifeste du module.

## Publication par CI

`.github/workflows/publier.yml` est un workflow réutilisable (`workflow_call`) : compile, signe, dépose APK +
`version.json` sur spdyn1.github.io/<dossier>, crée la release vX.Y. Appel depuis une appli :
`uses: spdyn1/maj/.github/workflows/publier.yml@main`, `secrets: inherit`, entrée `apks` = une ligne
`NOM dossier chemin/apk` par APK. Secrets : CARNET_KEYSTORE_B64, CARNET_KS_PASS, SITE_DEPLOY_KEY.
