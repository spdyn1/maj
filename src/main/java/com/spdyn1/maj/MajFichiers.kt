package com.spdyn1.maj

import androidx.core.content.FileProvider

/** FileProvider propre au module (autorité « <applicationId>.maj ») : ne gêne pas celui que l'appli déclarerait. */
class MajFichiers : FileProvider()
