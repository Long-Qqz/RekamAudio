package com.example.rekamaudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.system.ErrnoException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.system.OsConstants
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.rekamaudio.R
import com.example.rekamaudio.data.model.Mp3Bitrate
import com.example.rekamaudio.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import com.example.rekamaudio.data.model.AudioQuality
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import android.media.AudioFormat.CHANNEL_IN_STEREO
import android.system.Os
import android.media.AudioFormat.ENCODING_PCM_16BIT

@AndroidEntryPoint
class AudioCaptureService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var recordingJob: Job? = null
    @Volatile private var isRecording = false
    private var currentFileUri: Uri? = null
    private var audioQuality: AudioQuality = AudioQuality.MEDIUM_QUALITY_M4A

    private lateinit var overlayManager: OverlayManager
    private var savedResultCode: Int = 0
    private var savedResultData: Intent? = null
    private var isOverlayMode = false
    private var lastProgressNotifyMs: Long = 0L

    // Long recordings must not let the CPU doze off: without a wake lock the
    // recording thread stops being scheduled when the screen turns off and the
    // AudioRecord buffer silently overflows, leaving an empty file behind.
    private var wakeLock: PowerManager.WakeLock? = null

    // Guards against a "stop" arriving while startRecording() is still inside
    // its setup window: a stale setup must never resurrect a cancelled session.
    @Volatile private var sessionGeneration = 0

    // Consecutive AudioRecord.read() failures. A dead projection or a lost audio
    // source makes read() return a negative error code forever; without this the
    // capture loop would spin silently and produce an empty recording.
    private var consecutiveReadErrors = 0
    private var captureFailureNotified = false

    @Volatile private var totalBytesCaptured = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        overlayManager = OverlayManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != 0 && resultData != null) {
                    savedResultCode = resultCode
                    savedResultData = resultData
                    // A freshly granted consent token supersedes any cached
                    // projection; keeping the old one would capture silence.
                    if (!isRecording) stopProjection()

                    startForegroundServiceNotification()
                    startRecording(resultCode, resultData)
                    overlayManager.updateState(true) // Update overlay if active
                }
            }
            ACTION_STOP -> {
                stopRecording()
                overlayManager.updateState(false) // Update overlay
                if (!isOverlayMode) {
                    stopSelf()
                }
            }
            ACTION_SHOW_OVERLAY -> {
                 isOverlayMode = true
                 val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                 val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                
                if (resultCode != 0 && resultData != null) {
                    savedResultCode = resultCode
                    savedResultData = resultData
                    // A freshly granted consent token supersedes any cached
                    // projection; keeping the old one would capture silence.
                    if (!isRecording) stopProjection()
                }
                
                val qualityName = intent.getStringExtra(EXTRA_AUDIO_QUALITY)
                if (qualityName != null) {
                    try {
                        audioQuality = AudioQuality.valueOf(qualityName)
                    } catch (e: Exception) {
                        Log.e("AudioCaptureService", "Invalid AudioQuality: $qualityName")
                    }
                }
                
                startForegroundServiceNotification()
                
                overlayManager.showOverlay(
                    onRecordClick = {
                        if (savedResultCode != 0 && savedResultData != null) {
                            startRecording(savedResultCode, savedResultData!!)
                        } else {
                            Log.e("AudioCaptureService", "Missing MediaProjection Permission Data")
                        }
                    },
                    onStopClick = {
                        stopRecording()
                    },
                    onCloseClick = {
                        isOverlayMode = false
                        stopRecording()
                        overlayManager.removeOverlay()
                        stopSelf()
                    },
                    isRecording = isRecording
                )
            }
            ACTION_DISMISS_OVERLAY -> {
                isOverlayMode = false
                stopRecording()
                overlayManager.removeOverlay()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }
    
    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
             startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
             startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        }
    }

    private fun startRecording(resultCode: Int, resultData: Intent) {
        if (isRecording) {
            // Already recording
            return
        }

        // A MediaProjection consent token is single-use: once stop() is called
        // the same Intent can never be redeemed again. Reusing the live
        // projection across overlay sessions is what makes repeat recordings
        // work at all. Only fall back to redeeming the token on the first run.
        val projection = mediaProjection ?: runCatching {
            mediaProjectionManager?.getMediaProjection(resultCode, resultData)
        }.onFailure {
            Log.e("AudioCaptureService", "getMediaProjection failed", it)
        }.getOrNull()

        if (projection == null) {
            reportCaptureFailure(getString(R.string.capture_failed_title), "MediaProjection unavailable")
            return
        }
        if (mediaProjection == null) {
            mediaProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w("AudioCaptureService", "MediaProjection stopped by the system")
                    stopRecording()
                    // The projection object is dead. Drop it, otherwise the next
                    // recording reuses it and silently captures nothing.
                    stopProjection()
                    withMainThread { overlayManager.updateState(false) }
                }
            }, null)
        }

        val generation = ++sessionGeneration

        // Launch into background to allow delay and offload setup
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // CRITICAL FIX: Give the system time to register the projection
                // before asking for AudioPlaybackCapture. This prevents the "silent audio" race condition.
                kotlinx.coroutines.delay(500)

                // The user may have pressed stop while we were waiting; bail out
                // instead of resurrecting a recording they already cancelled.
                if (generation != sessionGeneration) {
                    Log.d("AudioCaptureService", "Recording setup cancelled before it started")
                    return@launch
                }

                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    // REMOVED .excludeUid() to allow self-recording for testing or internal audio general testing
                    .build()

                val sampleRate = 48000 // Native android sample rate
                val channelConfig = AudioFormat.CHANNEL_IN_STEREO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

                Log.d("AudioCaptureService", "Creating AudioRecord with bufferSize: $bufferSize")

                if (ContextCompat.checkSelfPermission(this@AudioCaptureService, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.e("AudioCaptureService", "Recording permission not granted")
                    reportCaptureFailure(getString(R.string.capture_failed_title), "RECORD_AUDIO permission not granted")
                    stopRecording() // This is running on IO thread
                    if (!isOverlayMode) stopSelf()
                    return@launch
                }

                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("AudioCaptureService", "AudioRecord failed to initialize")
                    record.release()
                    reportCaptureFailure(getString(R.string.capture_failed_title), "AudioRecord not initialized")
                    stopRecording()
                    if (!isOverlayMode) stopSelf()
                    return@launch
                }

                audioRecord = record

                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e("AudioCaptureService", "AudioRecord did not enter RECORDING state")
                    reportCaptureFailure(getString(R.string.capture_failed_title), "AudioRecord not recording")
                    stopRecording()
                    if (!isOverlayMode) stopSelf()
                    return@launch
                }

                // Reset per-session counters before the capture loops start.
                consecutiveReadErrors = 0
                captureFailureNotified = false
                totalBytesCaptured = 0L

                isRecording = true
                acquireWakeLock()
                getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_IS_RECORDING, true)
                    .apply()

                Log.d("AudioCaptureService", "AudioRecord started successfully")

                withContext(Dispatchers.Main) {
                    overlayManager.updateState(true)
                }

                val mp3Bitrate = settingsRepository.mp3Bitrate.first()
                val streamingEnabled = settingsRepository.streamingEncoding.first()

                recordingJob = launch {
                    when (audioQuality) {
                        AudioQuality.HIGH_QUALITY_WAV -> recordWavAudio(bufferSize)
                        AudioQuality.COMPATIBLE_QUALITY_MP3 -> {
                            if (streamingEnabled) {
                                recordMp3Streaming(bufferSize, mp3Bitrate)
                            } else {
                                recordMp3Audio(bufferSize, mp3Bitrate)
                            }
                        }
                        AudioQuality.MEDIUM_QUALITY_M4A -> recordAacAudio(bufferSize)
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioCaptureService", "Error starting recording", e)
                reportCaptureFailure(getString(R.string.capture_failed_title), e.javaClass.simpleName + ": " + e.message)
                stopRecording()
                if (!isOverlayMode) stopSelf()
            }
        }
    }

    private fun recordAacAudio(bufferSize: Int) {
        val uri = createMediaStoreEntry(isWav = false) ?: return
        currentFileUri = uri
        
        var pfd: ParcelFileDescriptor? = null
        // Declared outside the try so the finally block can tell the user when
        // nothing at all was captured.
        var totalBytesRead = 0L

        try {
             pfd = contentResolver.openFileDescriptor(uri, "w")
             val fd = pfd?.fileDescriptor ?: return

            // Setup MediaCodec setup (AAC Encoder)
            val mimeType = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mimeType, SAMPLE_RATE, CHANNEL_COUNT)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 192000) // 192kbps
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            
            mediaCodec = MediaCodec.createEncoderByType(mimeType)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            // Setup MediaMuxer with FileDescriptor
            mediaMuxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var isMuxerStarted = false
            
            val inputBuffer = ByteArray(bufferSize)
            var lastLogTime = 0L

            while (isRecording) {
                // Read PCM data
                val readResult = readAudioChunk(inputBuffer, bufferSize, "m4a")
                if (readResult < 0 && isCaptureBroken()) {
                    Log.e("AudioCaptureService", "Aborting M4A capture: AudioRecord unrecoverable")
                    break
                }

                if (readResult > 0) {
                    // Diagnostic Logging (every 2 seconds)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime > 2000) {
                         var maxAmp = 0
                         for (i in 0 until readResult step 2) {
                             if (i + 1 < readResult) {
                                  // Convert 2 bytes to 16-bit sample (Little Endian)
                                  val sample = ((inputBuffer[i+1].toInt() shl 8) or (inputBuffer[i].toInt() and 0xFF)).toShort()
                                  val absSample = abs(sample.toInt())
                                  if (absSample > maxAmp) maxAmp = absSample
                             }
                         }
                         Log.d("AudioCheck", "Recorded $readResult bytes, Max Amplitude: $maxAmp")
                         lastLogTime = currentTime
                    }

                    val inputBufferIndex = mediaCodec?.dequeueInputBuffer(10000) ?: -1
                    if (inputBufferIndex >= 0) {
                        val codecInputBuffer = mediaCodec?.getInputBuffer(inputBufferIndex)
                        codecInputBuffer?.clear()
                        codecInputBuffer?.put(inputBuffer, 0, readResult)
                        
                        // Calculate PTS based on samples processed
                        // PTS (us) = (TotalSamples / SampleRate) * 1,000,000
                        // TotalSamples = TotalBytes / (ChannelCount * 2 bytes/sample)
                        val presentationTimeUs = (totalBytesRead * 1_000_000) / (SAMPLE_RATE * CHANNEL_COUNT * 2)
                        
                        mediaCodec?.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            readResult,
                            presentationTimeUs,
                            0
                        )
                        
                        totalBytesRead += readResult
                    }
                }

                // Retrieve encoded data
                var outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                while (outputBufferIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0) {
                         if (!isMuxerStarted) {
                            val newFormat = mediaCodec?.outputFormat
                            trackIndex = mediaMuxer?.addTrack(newFormat!!) ?: -1
                            mediaMuxer?.start()
                            isMuxerStarted = true
                        }
                        
                        val encodedData = mediaCodec?.getOutputBuffer(outputBufferIndex)
                        if (encodedData != null) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            mediaMuxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                    }

                    mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                    outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
                }
            }
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Error encoding audio", e)
        } finally {
             try {
                mediaCodec?.stop()
                mediaCodec?.release()
                mediaMuxer?.stop()
                mediaMuxer?.release()
                pfd?.close()
                finalizeMediaStoreEntry(uri)
                if (totalBytesRead == 0L) {
                    Log.e("AudioCaptureService", "M4A recording captured 0 bytes")
                    reportCaptureFailure(
                        getString(R.string.capture_failed_title),
                        getString(R.string.capture_failed_text)
                    )
                }
            } catch (e: Exception) {
                Log.e("AudioCaptureService", "Error releasing resources", e)
            }
            mediaCodec = null
            mediaMuxer = null
            currentFileUri = null
        }
    }

    private fun recordWavAudio(bufferSize: Int) {
        val uri = createMediaStoreEntry(isWav = true) ?: return
        currentFileUri = uri
        
        var pfd: ParcelFileDescriptor? = null
        var outputStream: FileOutputStream? = null
        // Declared outside the try so the finally block can report a session
        // that captured nothing at all.
        var totalBytesRead = 0L

        try {
            pfd = contentResolver.openFileDescriptor(uri, "w")
            val fd = pfd?.fileDescriptor ?: return
            outputStream = FileOutputStream(fd)
            val channel = outputStream.channel

            // Write 44 bytes placeholder header
            val header = ByteArray(44)
            outputStream.write(header)

            val buffer = ByteArray(bufferSize)
            
            while (isRecording) {
                val readResult = readAudioChunk(buffer, bufferSize, "wav")
                if (readResult < 0 && isCaptureBroken()) {
                    Log.e("AudioCaptureService", "Aborting WAV capture: AudioRecord unrecoverable")
                    break
                }
                if (readResult > 0) {
                    outputStream.write(buffer, 0, readResult)
                    totalBytesRead += readResult.toLong()
                }
            }
            
            // Go back and write real header
            channel.position(0)
            writeWavHeader(channel, totalBytesRead, SAMPLE_RATE, CHANNEL_COUNT)
            
        } catch (e: Exception) {
             Log.e("AudioCaptureService", "Error recording WAV", e)
        } finally {
            try {
                outputStream?.close()
                pfd?.close()
                finalizeMediaStoreEntry(uri)
                if (totalBytesRead == 0L) {
                    Log.e("AudioCaptureService", "WAV recording captured 0 bytes")
                    reportCaptureFailure(
                        getString(R.string.capture_failed_title),
                        getString(R.string.capture_failed_text)
                    )
                }
            } catch (e: Exception) {
                Log.e("AudioCaptureService", "Error closing resources", e)
            }
        }
    }

    /**
     * Records raw PCM to a temp file while recording, then hands it off to an
     * async ffmpeg session for MP3 encoding. The encode runs on ffmpeg-kit's
     * own executor and is fully decoupled from the recording coroutine, so
     * cancelling that coroutine at stop time can never abort or crash it.
     */
    private fun recordMp3Audio(bufferSize: Int, bitrate: Mp3Bitrate) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val pcmFile = File(cacheDir, "rekam_tmp_$timestamp.pcm")
        var capturedBytes = 0L

        try {
            FileOutputStream(pcmFile).use { outputStream ->
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val readResult = readAudioChunk(buffer, bufferSize, "mp3")
                    if (readResult < 0 && isCaptureBroken()) {
                        Log.e("AudioCaptureService", "Aborting MP3 capture: AudioRecord unrecoverable")
                        break
                    }
                    if (readResult > 0) {
                        outputStream.write(buffer, 0, readResult)
                        capturedBytes += readResult
                    }
                }
                outputStream.flush()
            }
        } catch (t: Throwable) {
            Log.e("AudioCaptureService", "Error capturing PCM for MP3", t)
            if (pcmFile.length() == 0L) {
                pcmFile.delete()
                reportCaptureFailure(
                    getString(R.string.capture_failed_title),
                    t.javaClass.simpleName + ": " + t.message
                )
                return
            }
        }

        if (pcmFile.length() == 0L) {
            // Never drop the session silently: tell the user nothing was captured
            // instead of letting them believe a long recording is safe.
            Log.e("AudioCaptureService", "MP3 capture produced 0 bytes (captured=$capturedBytes)")
            pcmFile.delete()
            reportCaptureFailure(
                getString(R.string.capture_failed_title),
                getString(R.string.capture_failed_text)
            )
            return
        }

        encodeMp3Async(pcmFile, timestamp, bitrate)
    }

    /**
     * Real-time streaming MP3 encoding using a named pipe (FIFO).
     *
     * The raw PCM is ALWAYS written to a temp file alongside the FIFO. The live
     * pipe is only an optimisation: if ffmpeg fails to start, the pipe stalls or
     * back-pressures, the recording keeps landing on disk and is encoded after
     * the fact instead of being lost. Every blocking step here is bounded so a
     * dead ffmpeg can never freeze the capture loop.
     */
    private fun recordMp3Streaming(bufferSize: Int, bitrate: Mp3Bitrate) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fifoFile = File(cacheDir, "rekam_fifo_$timestamp")
        val pcmFile = File(cacheDir, "rekam_tmp_$timestamp.pcm")
        val mp3File = File(cacheDir, "rekam_tmp_$timestamp.mp3")

        try {
            Os.mkfifo(fifoFile.absolutePath, 0x1B6) // octal 0666
        } catch (e: ErrnoException) {
            Log.e("AudioCaptureService", "FIFO creation failed, using file capture", e)
            recordMp3Audio(bufferSize, bitrate)
            return
        }

        val state = StreamState()
        val encodeDone = CountDownLatch(1)
        val fifoQueue = ArrayBlockingQueue<ByteArray>(FIFO_QUEUE_CAPACITY)
        // Set once the capture loop is done and the pipe has been closed.
        val captureFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        // Guarantees the tail work runs exactly once, from whichever thread
        // (ffmpeg callback, watchdog or the stop path) gets there first.
        val finalized = java.util.concurrent.atomic.AtomicBoolean(false)

        var pcmOut: FileOutputStream? = null
        var fifoOut: java.io.OutputStream? = null
        var writer: Thread? = null

        /**
         * Turns the finished stream into a saved recording. This never blocks
         * the stop path: while ffmpeg is still flushing, its own completion
         * callback calls in here instead.
         */
        fun finalizeStream(streamOk: Boolean) {
            if (!finalized.compareAndSet(false, true)) return
            try {
                if (streamOk && !state.stalled.get() && mp3File.length() > 0L) {
                    copyFileToMediaStore(mp3File, "recording_$timestamp.mp3", "audio/mpeg")
                    Log.d("AudioCaptureService", "Streaming MP3 saved (${mp3File.length()} bytes)")
                    withMainThread { overlayManager.hideEncoding() }
                    restoreIdleNotification()
                    pcmFile.delete()
                    mp3File.delete()
                } else if (pcmFile.length() > 0L) {
                    Log.w(
                        "AudioCaptureService",
                        "Streaming path unusable (ok=$streamOk, stalled=${state.stalled.get()}), " +
                            "encoding ${pcmFile.length()} bytes of PCM backup"
                    )
                    withMainThread { overlayManager.showEncoding() }
                    encodeMp3Async(pcmFile, timestamp, bitrate, "${timestamp}_fb")
                } else {
                    Log.e("AudioCaptureService", "Streaming MP3 captured 0 bytes")
                    pcmFile.delete()
                    mp3File.delete()
                    withMainThread { overlayManager.hideEncoding() }
                    restoreIdleNotification()
                    reportCaptureFailure(
                        getString(R.string.capture_failed_title),
                        getString(R.string.capture_failed_text)
                    )
                }
            } catch (t: Throwable) {
                Log.e("AudioCaptureService", "Error finalising streaming MP3", t)
                withMainThread { overlayManager.hideEncoding() }
                restoreIdleNotification()
                reportCaptureFailure(
                    getString(R.string.capture_failed_title),
                    t.javaClass.simpleName + ": " + t.message
                )
            }
        }

        try {
            val pcmSink = FileOutputStream(pcmFile)
            pcmOut = pcmSink
            withMainThread { overlayManager.updateState(true) }

            val command = "-y -f s16le -ar $SAMPLE_RATE -ac $CHANNEL_COUNT " +
                "-i ${fifoFile.absolutePath} -codec:a libmp3lame -b:a ${bitrate.bitsPerSecond} ${mp3File.absolutePath}"

            val streamSession = FFmpegKit.executeAsync(
                command,
                { session ->
                    val ok = ReturnCode.isSuccess(session.returnCode)
                    state.encodeSucceeded.set(ok)
                    if (!ok) {
                        Log.e(
                            "AudioCaptureService",
                            "Streaming MP3 encode failed: rc=${session.returnCode} ${extractSessionError(session)}"
                        )
                    }
                    encodeDone.countDown()
                    // Only finish from here once the capture side has stopped;
                    // finalising mid-recording would touch the PCM file the
                    // capture loop is still writing to.
                    if (captureFinished.get()) finalizeStream(ok)
                },
                { /* logs ignored */ },
                { statistics ->
                    // Live progress while the session flushes, so the overlay
                    // can show a determinate bar instead of a spinner.
                    val durationMs = state.capturedBytes.get() * 1000L /
                        (SAMPLE_RATE.toLong() * CHANNEL_COUNT * 2L)
                    if (durationMs > 0L) {
                        val progress = (statistics.time / durationMs.toDouble())
                            .coerceIn(0.0, 1.0)
                            .toFloat()
                        withMainThread { overlayManager.updateEncodingProgress(progress) }
                    }
                }
            )

            // O_RDWR never blocks on a FIFO. Opening O_WRONLY without a reader
            // blocks forever, which used to freeze the capture loop and throw
            // away an entire recording when ffmpeg failed to start.
            val fifoFd = Os.open(fifoFile.absolutePath, OsConstants.O_RDWR, 0)
            val sink = FileOutputStream(fifoFd)
            fifoOut = sink

            writer = Thread {
                try {
                    // Exits as soon as the feed is closed, after having drained
                    // everything the capture loop already queued.
                    while (!state.feedClosed.get()) {
                        val chunk = fifoQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        sink.write(chunk)
                    }
                } catch (t: Throwable) {
                    // Exceptions after we deliberately closed the pipe are part
                    // of shutting down. Only a failure while we still wanted to
                    // stream means the pipe is genuinely broken.
                    if (!state.feedClosed.get()) {
                        Log.e("AudioCaptureService", "FIFO writer stopped, falling back to file capture", t)
                        state.stalled.set(true)
                    }
                }
            }.apply {
                isDaemon = true
                name = "rekam-fifo-writer"
                start()
            }

            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val readResult = readAudioChunk(buffer, bufferSize, "mp3-stream")
                if (readResult < 0 && isCaptureBroken()) {
                    Log.e("AudioCaptureService", "Aborting streaming MP3 capture: AudioRecord unrecoverable")
                    break
                }
                if (readResult <= 0) continue

                // Disk copy first: it must survive whatever the pipe does.
                pcmSink.write(buffer, 0, readResult)
                state.capturedBytes.addAndGet(readResult.toLong())

                if (!state.stalled.get()) {
                    val copy = buffer.copyOf(readResult)
                    if (!fifoQueue.offer(copy, FIFO_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        Log.w("AudioCaptureService", "FIFO back-pressure detected, keeping file capture")
                        state.stalled.set(true)
                        fifoQueue.clear()
                    }
                }
            }
            pcmSink.flush()
            pcmOut?.close()
            pcmOut = null

            // Let the pipe drain whatever is still queued so the MP3 does not
            // lose its tail, then signal EOF so ffmpeg can flush and exit.
            val drainDeadline = SystemClock.elapsedRealtime() + FIFO_DRAIN_TIMEOUT_MS
            while (fifoQueue.isNotEmpty() &&
                !state.stalled.get() &&
                SystemClock.elapsedRealtime() < drainDeadline
            ) {
                Thread.sleep(20)
            }

            state.feedClosed.set(true)
            // Let the writer finish the chunk it may already be sending before we
            // tear the pipe down; closing underneath a live write would surface
            // as a spurious "stalled" and force a needless full re-encode.
            runCatching { writer?.join(FIFO_JOIN_TIMEOUT_MS) }
            runCatching { fifoOut?.close() }
            fifoOut = null

            // Show the finishing progress bar and hand off to ffmpeg.
            withMainThread { overlayManager.showEncoding() }
            captureFinished.set(true)

            // Bounded grace period. A healthy pipe delivers EOF right away and
            // this returns in a few hundred milliseconds. If the pipe never got
            // its EOF (writer stuck in a blocking write) the session would hang
            // forever, so we cancel it and encode the complete PCM backup
            // instead — the recording is never lost and the ring never spins.
            val streamFinished = runCatching {
                encodeDone.await(STREAM_FINISH_GRACE_SECONDS, TimeUnit.SECONDS)
            }.getOrDefault(false)

            if (streamFinished) {
                // No-op when the completion callback already finalised.
                finalizeStream(state.encodeSucceeded.get())
            } else {
                Log.w("AudioCaptureService", "Streaming encode did not finish in time, cancelling it")
                runCatching { FFmpegKit.cancel(streamSession.sessionId) }
                state.stalled.set(true)
                finalizeStream(false)
            }
        } catch (t: Throwable) {
            Log.e("AudioCaptureService", "Error in streaming MP3 recording", t)
            runCatching { pcmOut?.flush() }
            state.stalled.set(true)
            captureFinished.set(true)
            finalizeStream(false)
        } finally {
            state.feedClosed.set(true)
            runCatching { fifoOut?.close() }
            runCatching { pcmOut?.close() }
            runCatching { writer?.join(FIFO_JOIN_TIMEOUT_MS) }
            runCatching { fifoFile.delete() }
        }
    }

    /** Mutable state shared between the capture loop, the pipe writer and ffmpeg. */
    private class StreamState {
        val stalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val encodeSucceeded = java.util.concurrent.atomic.AtomicBoolean(false)
        val feedClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        val capturedBytes = java.util.concurrent.atomic.AtomicLong(0L)
    }

    /**
     * [outputTag] keeps the temporary MP3 path distinct from the streaming
     * session's output so a fallback re-encode can never race with it.
     */
    private fun encodeMp3Async(
        pcmFile: File,
        timestamp: String,
        bitrate: Mp3Bitrate,
        outputTag: String = timestamp
    ) {
        val mp3File = File(cacheDir, "rekam_tmp_$outputTag.mp3")
        val totalDurationMs = pcmFile.length() * 1000L / (SAMPLE_RATE * CHANNEL_COUNT * 2L)

        try {
            overlayManager.showEncoding()
            updateEncodingNotification(0f)

            val command = "-y -f s16le -ar $SAMPLE_RATE -ac $CHANNEL_COUNT " +
                "-i ${pcmFile.absolutePath} -codec:a libmp3lame -b:a ${bitrate.bitsPerSecond} ${mp3File.absolutePath}"

            FFmpegKit.executeAsync(
                command,
                { session ->
                    // Completion callback runs on ffmpeg-kit's executor thread.
                    try {
                        if (ReturnCode.isSuccess(session.returnCode)) {
                            copyFileToMediaStore(mp3File, "recording_$timestamp.mp3", "audio/mpeg")
                        } else {
                            val detail = extractSessionError(session)
                            Log.e("AudioCaptureService", "MP3 encode failed: rc=${session.returnCode} $detail")
                            val savedWav = savePcmAsWav(pcmFile, timestamp)
                            showEncodeErrorNotification(detail, savedWav)
                        }
                    } catch (t: Throwable) {
                        // Errors (e.g. failed native library init) must never crash the process.
                        Log.e("AudioCaptureService", "Error finishing MP3 encode", t)
                        val savedWav = savePcmAsWav(pcmFile, timestamp)
                        showEncodeErrorNotification("${t.javaClass.simpleName}: ${t.message}", savedWav)
                    } finally {
                        pcmFile.delete()
                        mp3File.delete()
                        overlayManager.hideEncoding()
                        restoreIdleNotification()
                    }
                },
                { /* logs ignored */ },
                { newStatistics ->
                    if (totalDurationMs > 0) {
                        val progress = (newStatistics.time / totalDurationMs).toFloat().coerceIn(0f, 1f)
                        overlayManager.updateEncodingProgress(progress)
                        updateEncodingNotification(progress)
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e("AudioCaptureService", "Error starting MP3 encoding", t)
            val savedWav = savePcmAsWav(pcmFile, timestamp)
            pcmFile.delete()
            mp3File.delete()
            overlayManager.hideEncoding()
            restoreIdleNotification()
            showEncodeErrorNotification("${t.javaClass.simpleName}: ${t.message}", savedWav)
        }
    }

    /**
     * Fallback when MP3 encoding fails: wraps the raw PCM capture in a WAV
     * header so the user never loses the recording.
     */
    private fun savePcmAsWav(pcmFile: File, timestamp: String): Boolean {
        return try {
            val wavFile = File(cacheDir, "rekam_tmp_$timestamp.wav")
            FileOutputStream(wavFile).use { out ->
                writeWavHeader(out.channel, pcmFile.length(), SAMPLE_RATE, CHANNEL_COUNT)
                pcmFile.inputStream().use { it.copyTo(out) }
            }
            val saved = wavFile.length() > 44
            if (saved) {
                copyFileToMediaStore(wavFile, "recording_$timestamp.wav", "audio/wav")
            }
            wavFile.delete()
            saved
        } catch (t: Throwable) {
            Log.e("AudioCaptureService", "Failed to save WAV fallback", t)
            false
        }
    }

    /** Pulls the most relevant error lines out of an ffmpeg session log. */
    private fun extractSessionError(session: com.arthenica.ffmpegkit.FFmpegSession): String {
        return try {
            val logs = session.getAllLogsAsString()
                .lineSequence()
                .filter { line ->
                    val l = line.lowercase()
                    ("error" in l) || ("unknown" in l) || ("not found" in l) ||
                        ("no such" in l) || ("permission" in l) || ("invalid" in l) ||
                        ("failed" in l) || ("unable" in l)
                }
                .toList()
                .takeLast(4)
                .joinToString(" | ")
                .take(300)
            if (logs.isBlank()) "rc=${session.returnCode}" else logs
        } catch (t: Throwable) {
            "rc=${session.returnCode}"
        }
    }

    private fun updateEncodingNotification(progress: Float) {
        val now = SystemClock.elapsedRealtime()
        if (progress < 1f && now - lastProgressNotifyMs < 400) return
        lastProgressNotifyMs = now

        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.encoding_notification_title))
            .setContentText(getString(R.string.encoding_notification_text, percent))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setProgress(100, percent, false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        try {
            notificationManager().notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to update encoding notification", e)
        }
    }

    private fun restoreIdleNotification() {
        try {
            notificationManager().notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to restore notification", e)
        }
    }

    private fun showEncodeErrorNotification(detail: String, wavSaved: Boolean) {
        val title = getString(
            if (wavSaved) R.string.encode_error_wav_title else R.string.encode_error_title
        )
        val summary = getString(
            if (wavSaved) R.string.encode_error_wav_text else R.string.encode_error_text
        )
        val fullText = "$summary\n$detail"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager().notify(ENCODE_ERROR_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to show error notification", e)
        }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun copyFileToMediaStore(file: File, fileName: String, mimeType: String) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/RekamAudio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val collection =
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val uri = contentResolver.insert(collection, values) ?: run {
            Log.e("AudioCaptureService", "Failed to create MediaStore entry for $fileName")
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            val doneValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, doneValues, null, null)
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to write $fileName to MediaStore", e)
            contentResolver.delete(uri, null, null)
        }
    }

    private fun writeWavHeader(channel: FileChannel, totalAudioLen: Long, sampleRate: Int, channels: Int) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * 16 / 8).toLong()
        
        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen.toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1) // AudioFormat 1 = PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate.toInt())
        buffer.putShort((channels * 16 / 8).toShort()) // BlockAlign
        buffer.putShort(16) // BitsPerSample
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLen.toInt())
        
        channel.write(ByteBuffer.wrap(header))
    }

    private fun createMediaStoreEntry(isWav: Boolean): Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = if (isWav) "recording_$timestamp.wav" else "recording_$timestamp.m4a"
        val mimeType = if (isWav) "audio/wav" else "audio/mp4a-latm"
        
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/RekamAudio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        
        val collection =
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        return contentResolver.insert(collection, values)
    }

    private fun finalizeMediaStoreEntry(uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        contentResolver.update(uri, values, null, null)
    }

    private fun withMainThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    /**
     * Long recordings must keep the CPU awake. Without a wake lock the capture
     * thread stops being scheduled once the screen turns off, the AudioRecord
     * ring buffer overflows, and the user ends up with a silent empty file
     * after recording for an hour.
     */
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:recording"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d("AudioCaptureService", "Wake lock acquired")
        } catch (t: Throwable) {
            Log.e("AudioCaptureService", "Failed to acquire wake lock", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            Log.e("AudioCaptureService", "Failed to release wake lock", t)
        }
        wakeLock = null
    }

    /**
     * Reads one PCM chunk and turns silent AudioRecord failures into a loud,
     * user-visible problem. Previously a negative return code was treated as
     * "no data", so a dead audio source spun the loop forever and produced an
     * empty recording without any signal to the user.
     */
    private fun readAudioChunk(buffer: ByteArray, size: Int, tag: String): Int {
        val record = audioRecord ?: return AudioRecord.ERROR_INVALID_OPERATION
        val result = try {
            record.read(buffer, 0, size)
        } catch (t: Throwable) {
            Log.e("AudioCaptureService", "[$tag] AudioRecord.read threw", t)
            AudioRecord.ERROR_INVALID_OPERATION
        }
        if (result < 0) {
            consecutiveReadErrors++
            Log.e(
                "AudioCaptureService",
                "[$tag] AudioRecord.read error=$result (consecutive=$consecutiveReadErrors)"
            )
            if (consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS) {
                notifyCaptureFailureOnce()
            }
        } else {
            consecutiveReadErrors = 0
            if (result > 0) totalBytesCaptured += result
        }
        return result
    }

    private fun isCaptureBroken(): Boolean =
        consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS

    private fun notifyCaptureFailureOnce() {
        if (captureFailureNotified) return
        captureFailureNotified = true
        reportCaptureFailure(
            getString(R.string.capture_failed_title),
            getString(R.string.capture_failed_text)
        )
    }

    /**
     * Surfaces a capture problem to the user. Silence is the worst possible
     * outcome: they must learn right away that this session is not recording.
     */
    private fun reportCaptureFailure(title: String, detail: String) {
        Log.e("AudioCaptureService", "Capture failure: $title / $detail")
        withMainThread { overlayManager.updateState(false) }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager().notify(CAPTURE_ERROR_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Failed to show capture failure notification", e)
        }
    }

    private fun stopRecording() {
        isRecording = false
        // Invalidate any half-finished setup so it can never resurrect a session
        // the user already stopped.
        sessionGeneration++
        getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_RECORDING, false)
            .apply()

        val job = recordingJob
        recordingJob = null
        job?.cancel()

        // The capture loop notices isRecording == false immediately, but it may
        // still be inside a blocking AudioRecord.read(). Tearing the device down
        // underneath that call is undefined behaviour, so the release is deferred
        // until the loop has left it.
        val record = audioRecord
        audioRecord = null
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { withTimeoutOrNull(AUDIO_RELEASE_TIMEOUT_MS) { job?.join() } }
            runCatching { record?.stop() }
                .onFailure { Log.e("AudioCaptureService", "Error stopping audioRecord", it) }
            runCatching { record?.release() }
                .onFailure { Log.e("AudioCaptureService", "Error releasing audioRecord", it) }
        }

        releaseWakeLock()

        // A MediaProjection consent token is single-use. In overlay mode the
        // projection must stay alive so the next recording can reuse it —
        // stopping it here is what made every second recording come out empty.
        if (!isOverlayMode) {
            stopProjection()
        }
    }

    private fun stopProjection() {
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e("AudioCaptureService", "Error stopping mediaProjection", e)
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Whatever brought the service down, never leave a recording half-open
        // or a wake lock behind.
        stopRecording()
        isOverlayMode = false
        stopProjection()
        releaseWakeLock()
        overlayManager.removeOverlay()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(getString(R.string.notification_recording_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "AudioCaptureChannel"
        const val NOTIFICATION_ID = 1
        const val ENCODE_ERROR_NOTIFICATION_ID = 2
        const val CAPTURE_ERROR_NOTIFICATION_ID = 3

        /** How many consecutive AudioRecord.read() failures mark the source dead. */
        private const val MAX_CONSECUTIVE_READ_ERRORS = 20

        /** Bounded pipe queue so a stalled reader can never block the capture loop. */
        private const val FIFO_QUEUE_CAPACITY = 32
        private const val FIFO_OFFER_TIMEOUT_MS = 40L
        private const val FIFO_JOIN_TIMEOUT_MS = 1000L
        private const val FIFO_DRAIN_TIMEOUT_MS = 1500L

        /** How long the stop path waits for ffmpeg before cancelling it outright. */
        private const val STREAM_FINISH_GRACE_SECONDS = 6L

        /** Upper bound on waiting for the capture loop before releasing the device. */
        private const val AUDIO_RELEASE_TIMEOUT_MS = 1500L
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_SHOW_OVERLAY = "SHOW_OVERLAY"
        const val ACTION_DISMISS_OVERLAY = "DISMISS_OVERLAY" // New Action
        const val EXTRA_RESULT_CODE = "RESULT_CODE"
        const val EXTRA_RESULT_DATA = "RESULT_DATA"
        const val SAMPLE_RATE = 48000
        const val CHANNEL_COUNT = 2
        const val EXTRA_AUDIO_QUALITY = "AUDIO_QUALITY"
        
        const val PREFS_NAME = "rekam_audio_prefs"
        const val KEY_IS_RECORDING = "is_recording"
    }
}
