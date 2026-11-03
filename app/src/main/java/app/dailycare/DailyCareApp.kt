package app.dailycare

import android.app.Application
import android.content.Context
import app.dailycare.data.CareRepository
import app.dailycare.notify.Notifier
import app.dailycare.sensing.SignalHub

class DailyCareApp : Application() {
    lateinit var repository: CareRepository
        private set
    lateinit var signals: SignalHub
        private set
    lateinit var notifier: Notifier
        private set

    override fun onCreate() {
        super.onCreate()
        repository = CareRepository(this)
        signals = SignalHub(this, repository.state)
        notifier = Notifier(this)
        notifier.ensureChannels()
    }
}

val Context.careApp: DailyCareApp get() = applicationContext as DailyCareApp

const val TAG = "DailyCare"
