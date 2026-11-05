package com.inkspire.ebookreader

import android.app.Application
import com.inkspire.ebookreader.di.KoinModule
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.kotzilla.generated.monitoring
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@App)
            monitoring()
            modules(
                KoinModule.ttsModule,
                KoinModule.databaseModule,
                KoinModule.repositoryModule,
                KoinModule.useCaseModule,
                KoinModule.viewModelModule,
            )
        }
        PDFBoxResourceLoader.init(this@App)
    }
}