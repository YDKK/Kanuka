package YDKK.kanuka.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class GemmaAudioRecorderManager(
    private val onStatusChanged: (String) -> Unit
) {
    @Volatile
    private var isRecording = false

    private val pcmLock = Any()
    private var pcmStream = ByteArrayOutputStream()
    private var audioRecord: AudioRecord? = null
    private var readThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) {
            stopRecordingAndGetPcm()
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            onStatusChanged("Gemma audio capture initialization failed.")
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (_: SecurityException) {
            onStatusChanged("Microphone permission is required for audio capture.")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            onStatusChanged("Gemma audio capture could not open microphone.")
            return false
        }

        synchronized(pcmLock) {
            pcmStream = ByteArrayOutputStream()
        }
        audioRecord = record
        isRecording = true

        runCatching { record.startRecording() }.onFailure {
            isRecording = false
            audioRecord = null
            runCatching { record.release() }
            onStatusChanged("Gemma audio capture start failed.")
            return false
        }

        readThread = thread(
            start = true,
            name = "GemmaAudioRecorderThread"
        ) {
            readLoop(record, minBuffer)
        }

        onStatusChanged("Recording audio for Gemma 3n...")
        return true
    }

    fun stopRecordingAndBuildWav(): ByteArray? {
        val pcmBytes = stopRecordingAndGetPcm()
        if (pcmBytes.isEmpty()) return null
        return pcm16ToWav(pcmBytes)
    }

    fun stopRecording() {
        stopRecordingAndGetPcm()
    }

    fun destroy() {
        stopRecording()
    }

    private fun readLoop(
        record: AudioRecord,
        minBuffer: Int
    ) {
        val readBuffer = ByteArray(minBuffer.coerceAtLeast(4096))
        while (isRecording) {
            val read = record.read(readBuffer, 0, readBuffer.size)
            if (read <= 0) continue
            synchronized(pcmLock) {
                val currentSize = pcmStream.size()
                val remaining = MAX_PCM_BYTES - currentSize
                if (remaining <= 0) continue
                val writeBytes = read.coerceAtMost(remaining)
                pcmStream.write(readBuffer, 0, writeBytes)
            }
        }
    }

    private fun stopRecordingAndGetPcm(): ByteArray {
        if (!isRecording && audioRecord == null) {
            return ByteArray(0)
        }

        isRecording = false
        val record = audioRecord
        audioRecord = null

        runCatching { record?.stop() }
        val worker = readThread
        readThread = null
        runCatching { worker?.join(300) }
        runCatching { record?.release() }

        return synchronized(pcmLock) {
            val bytes = pcmStream.toByteArray()
            pcmStream.reset()
            bytes
        }
    }

    private fun pcm16ToWav(pcmBytes: ByteArray): ByteArray {
        val byteRate = SAMPLE_RATE_HZ * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        val dataLength = pcmBytes.size
        val totalLength = WAV_HEADER_SIZE + dataLength
        val out = ByteArrayOutputStream(totalLength)

        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(dataLength + 36)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort()) // PCM
            putShort(CHANNEL_COUNT.toShort())
            putInt(SAMPLE_RATE_HZ)
            putInt(byteRate)
            putShort((CHANNEL_COUNT * BITS_PER_SAMPLE / 8).toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLength)
        }.array()

        out.write(header)
        out.write(pcmBytes)
        return out.toByteArray()
    }

    private companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44
        private const val MAX_RECORD_SECONDS = 30
        private const val MAX_PCM_BYTES = SAMPLE_RATE_HZ * CHANNEL_COUNT * (BITS_PER_SAMPLE / 8) * MAX_RECORD_SECONDS
    }
}
