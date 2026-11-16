package fr.perso.redgifsdl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service de premier plan qui exécute les téléchargements.
 * Contrairement à un simple thread de l'activité, il continue quand l'écran est verrouillé
 * ou l'appli en arrière-plan : Android ne coupe ni le processeur ni le réseau d'un service
 * de premier plan, et on garde en plus un verrou de veille (CPU) et un verrou Wi-Fi.
 *
 * Les liens envoyés pendant un téléchargement sont mis en file d'attente.
 * Discrétion : la notification ne contient aucun nom de fichier et n'apparaît pas sur l'écran de verrouillage.
 */
class DownloadService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var notifications: NotificationManager
    private var lastNotifUpdate = 0L
    private var progressText = "Préparation…"
    // Résumés des liens traités depuis que la file était vide (pour un bilan groupé)
    private val batch = ArrayList<Pair<String, Boolean>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        createChannels()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Doit être appelé rapidement après startForegroundService(), même si le lien est invalide.
        startInForeground()
        val target = intent?.getStringExtra(EXTRA_TEXT)?.let(RedgifsApi::parse)
        if (target == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        val count = pending.incrementAndGet()
        DownloadState.update {
            busy = true
            queued = count - 1
        }
        executor.execute { runJob(target) }
        return START_NOT_STICKY
    }

    private fun runJob(target: RedgifsApi.Target) {
        DownloadState.update {
            status = "Connexion à RedGIFs…"
            statusColor = null
            fraction = null
        }
        progressText = "Connexion…"
        updateProgressNotification(null, force = true)

        val listener = object : Downloader.Listener {
            override fun onStatus(text: String) = DownloadState.update {
                status = text
                statusColor = null
            }

            override fun onProgress(fraction: Float?) {
                DownloadState.update { this.fraction = fraction }
                updateProgressNotification(fraction)
            }

            override fun onItem(index: Int, total: Int) {
                progressText = if (total > 1) "Téléchargement ${index + 1}/$total" else "Téléchargement en cours"
                updateProgressNotification(null, force = true)
            }
        }

        var (message, success, short) = try {
            val outcome = Downloader(this, listener).run(target)
            Triple((if (outcome.success) "✔ " else "✖ ") + outcome.message, outcome.success, outcome.short)
        } catch (e: Exception) {
            val label = (target as? RedgifsApi.Target.Collection)?.let { "Collection ${it.id}" } ?: "Vidéo"
            Triple("✖ ${Downloader.describe(e)}", false, "$label : ${Downloader.describe(e)}")
        }
        synchronized(batch) { batch += short to success }
        val left = pending.decrementAndGet()
        if (left == 0) {
            synchronized(batch) {
                if (batch.size > 1) {
                    // Plusieurs liens d'affilée (ex. bouton "Mettre à jour") : bilan de l'ensemble
                    success = batch.all { it.second }
                    message = (if (success) "✔ " else "✖ ") + "Terminé (${batch.size} liens) :\n" +
                        batch.joinToString("\n") { (if (it.second) "• " else "✖ ") + it.first }
                }
                batch.clear()
            }
        }
        DownloadState.update {
            status = message
            statusColor = if (success) R.color.ok else R.color.error
            fraction = null
            queued = maxOf(0, left - 1)
        }
        showDoneNotification(success)
        if (left == 0) main.post { stopIfIdle() }
    }

    private fun stopIfIdle() {
        if (pending.get() > 0) return
        DownloadState.update {
            busy = false
            queued = 0
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId) // ne s'arrête pas si un nouveau lien est arrivé entre-temps
    }

    /** Android 15+ : limite de durée des services "dataSync" atteinte (6 h par jour). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadState.update {
            busy = false
            status = "✖ Téléchargement interrompu par Android (durée maximale atteinte). Relance-le plus tard."
            statusColor = R.color.error
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        releaseLocks()
        super.onDestroy()
    }

    // --- Verrous : CPU et Wi-Fi restent actifs écran éteint ---

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redDL:download")
            .apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 60 * 1000L) // sécurité : relâché automatiquement au bout de 3 h
            }
        wifiLock = runCatching {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifi.createWifiLock(mode, "redDL:download").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    // --- Notifications (discrètes) ---

    private fun createChannels() {
        val progress = NotificationChannel(CHANNEL_PROGRESS, "Téléchargements en cours", NotificationManager.IMPORTANCE_LOW)
        progress.lockscreenVisibility = Notification.VISIBILITY_SECRET
        progress.setShowBadge(false)
        val done = NotificationChannel(CHANNEL_DONE, "Téléchargements terminés", NotificationManager.IMPORTANCE_DEFAULT)
        done.lockscreenVisibility = Notification.VISIBILITY_SECRET
        done.setSound(null, null)
        notifications.createNotificationChannels(listOf(progress, done))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildProgress(fraction: Float?): Notification {
        val queuedNote = (pending.get() - 1).takeIf { it > 0 }?.let { " (+$it en attente)" } ?: ""
        return Notification.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(progressText + queuedNote)
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun startInForeground() {
        val n = buildProgress(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS, n)
        }
    }

    /** Limité à ~1 mise à jour par seconde (Android ignore les mises à jour trop fréquentes). */
    private fun updateProgressNotification(fraction: Float?, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifUpdate < 1000) return
        lastNotifUpdate = now
        notifications.notify(NOTIF_PROGRESS, buildProgress(fraction))
    }

    private fun showDoneNotification(success: Boolean) {
        val n = Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (success) "Téléchargement terminé" else "Téléchargement terminé avec des erreurs")
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent())
            .build()
        notifications.notify(NOTIF_DONE, n)
    }

    companion object {
        const val EXTRA_TEXT = "text"
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2

        fun start(context: Context, text: String) {
            context.startForegroundService(Intent(context, DownloadService::class.java).putExtra(EXTRA_TEXT, text))
        }
    }
}
