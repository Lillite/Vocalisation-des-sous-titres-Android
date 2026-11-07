package fr.soustitresvoix.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi

/** Retains computation across UI recreation without retaining the old Activity or its controls. */
@UnstableApi
internal class LocalSrtTranslationOwner(context: Context,
    factory: (Context, (LocalSrtTranslationJob.State) -> Unit) -> LocalSrtTranslationJob =
        { app, changed -> LocalSrtTranslationJob(app, changed) }
) : ViewModel(), AutoCloseable {
    private var observer: ((LocalSrtTranslationJob.State) -> Unit)? = null
    private var observerGeneration = 0L
    val job = factory(context.applicationContext) { state -> observer?.invoke(state) }

    fun attach(changed: (LocalSrtTranslationJob.State) -> Unit): AutoCloseable {
        val generation = ++observerGeneration
        observer = changed
        return AutoCloseable { if (observerGeneration == generation) observer = null }
    }

    override fun close() {
        observer = null
        observerGeneration++
        job.close()
    }

    override fun onCleared() = close()

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val application = context.applicationContext
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == LocalSrtTranslationOwner::class.java)
            @Suppress("UNCHECKED_CAST")
            return LocalSrtTranslationOwner(application) as T
        }
    }
}
