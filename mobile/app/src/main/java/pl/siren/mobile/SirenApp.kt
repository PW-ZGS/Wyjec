package pl.siren.mobile

import android.app.Application
import pl.siren.mobile.core.SirenEngine

class SirenApp : Application() {
    lateinit var engine: SirenEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = SirenEngine(this)
    }
}
