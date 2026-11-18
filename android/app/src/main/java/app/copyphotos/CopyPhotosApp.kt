package app.copyphotos

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.copyphotos.core.CopyService
import app.copyphotos.core.DestinationService
import app.copyphotos.data.CopyLedger
import app.copyphotos.data.DestinationPreferences
import app.copyphotos.data.PasswordStore
import app.copyphotos.media.MediaDetailsReader
import app.copyphotos.media.MediaRepository
import app.copyphotos.remote.SmbConnector
import app.copyphotos.ui.DestinationViewModel
import app.copyphotos.ui.PhotoDetailsViewModel
import app.copyphotos.ui.PhotosViewModel
import app.copyphotos.work.AutoCopyTriggerWorker
import app.copyphotos.work.CopyJobs
import app.copyphotos.work.CopyScheduler
import app.copyphotos.work.CopyWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

/**
 * Composition root: one of each shared object, used by the ViewModels and the workers. The
 * destination's settings are reached only through [DestinationService], the password is read
 * only by the connector, and the ledger is reached only through [CopyService].
 *
 * @param context the app context.
 */
class AppGraph(context: Context) {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val passwords = PasswordStore(settings)
    private val workManager = WorkManager.getInstance(context)
    val destination = DestinationService(DestinationPreferences(settings, passwords), SmbConnector(passwords))
    val media = MediaRepository(context)
    private val detailsReader = MediaDetailsReader(context)
    val copyService = CopyService(media, media, CopyLedger(context), destination)
    val scheduler = CopyScheduler(context, workManager)
    private val jobs = CopyJobs(destination, scheduler, workManager, settings)

    /** Makes the screens' controllers, each with what it uses. */
    val viewModels: ViewModelProvider.Factory = viewModelFactory {
        initializer { PhotosViewModel(copyService, jobs, media, destination) }
        initializer { DestinationViewModel(destination, scheduler) }
        initializer { PhotoDetailsViewModel(copyService, detailsReader) }
    }
}

/**
 * Makes the workers, each with what it uses.
 *
 * @param graph the composition root, read only when WorkManager asks for a worker.
 */
private class CopyWorkerFactory(private val graph: () -> AppGraph) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        CopyWorker::class.java.name -> CopyWorker(appContext, workerParameters, graph().copyService)
        AutoCopyTriggerWorker::class.java.name ->
            AutoCopyTriggerWorker(appContext, workerParameters, graph().destination, graph().scheduler)
        else -> null
    }
}

/** The app: builds the composition root, and gives WorkManager and Coil their configuration. */
class CopyPhotosApp : Application(), ImageLoaderFactory, Configuration.Provider {
    /** The composition root, made on first use. */
    val graph by lazy { AppGraph(this) }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(CopyWorkerFactory { graph }).build()

    override fun onCreate() {
        super.onCreate()
        CopyWorker.ensureChannel(this)
        graph.scheduler.applyAutoCopy(graph.destination.config.value)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
