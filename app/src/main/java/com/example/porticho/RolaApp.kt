package com.example.porticho

import android.app.Application

/** El controlador vive en la aplicación (no en la pantalla), así la música sigue al cerrar la UI. */
class RolaApp : Application() {
    val controller by lazy { RolaController(this) }
}
