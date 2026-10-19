package me.d3sox.nevumi

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.settings.MusicPreferences
import org.junit.rules.ExternalResource

class ShowOptionsButtonsRule : ExternalResource() {
    private val application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication

    private val controller
        get() = application.controller

    private var original = false

    override fun before() = runBlocking {
        original = MusicPreferences(application.store).load().showMoreButtons
        controller.updatePreferences { it.copy(showMoreButtons = true) }.join()
    }

    override fun after() = runBlocking {
        controller.updatePreferences { it.copy(showMoreButtons = original) }.join()
    }
}
