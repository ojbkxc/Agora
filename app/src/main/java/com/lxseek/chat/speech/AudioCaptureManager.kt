package com.lxseek.chat.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.lxseek.chat.util.AppLog as Log
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioCaptureManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var outputFile: File? = null
    private var pcmOutputStream: FileOutputStream? = null

    private val bufferSize: Int by lazy {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        minBufferSize * BUFFER_SIZE_FACTOR
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startCapture(): Flow<ByteArray> = callbackFlow {
        if (!hasRecordPermission()) {
            close(SecurityException("RECORD_AUDIO permission not granted"))
            return@callbackFlow
        }

        if (isRecording) {
            Log.w(TAG, "Stale recording detected, forcing cleanup before restart")
            stopRecordingInternal()
        }

        try {
            outputFile = File(context.cacheDir, "audio_${System.currentTimeMillis()}.pcm")
            pcmOutputStream = FileOutputStream(outputFile)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                close(IllegalStateException("AudioRecord failed to initialize"))
                return@callbackFlow
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.i(TAG, "Audio capture started")

            val buffer = ByteArray(bufferSize)

            while (isActive && isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: -1

                if (bytesRead > 0) {
                    val chunk = buffer.copyOf(bytesRead)
                    pcmOutputStream?.write(chunk)
                    trySend(chunk)
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "Invalid operation during audio read")
                    break
                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Bad value during audio read")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during audio capture", e)
            close(e)
        }

        awaitClose {
            stopRecordingInternal()
        }
    }.flowOn(Dispatchers.IO)

    fun stopCapture(): File {
        stopRecordingInternal()

        val pcmFile = outputFile ?: throw IllegalStateException("No recording in progress")
        val wavFile = File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")

        try {
            convertPcmToWav(pcmFile, wavFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert PCM to WAV", e)
            return wavFile
        }

        pcmFile.delete()
        return wavFile
    }

    fun cancelCapture() {
        stopRecordingInternal()
        outputFile?.delete()
        outputFile = null
    }

    fun release() {
        stopRecordingInternal()
        audioRecord?.release()
        audioRecord = null
    }

    fun isCapturing(): Boolean = isRecording

    private fun stopRecordingInternal() {
        isRecording = false

        try {
            audioRecord?.stop()
            pcmOutputStream?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }

        pcmOutputStream = null

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        Log.i(TAG, "Audio capture stopped")
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun convertPcmToWav(pcmFile: File, wavFile: File) {
        val pcmData = pcmFile.readBytes()
        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = SAMPLE_RATE * channels * 2L

        FileOutputStream(wavFile).use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToByteArray(totalDataLen.toInt()))
            out.write("WAVE".toByteArray())

            out.write("fmt ".toByteArray())
            out.write(intToByteArray(16))
            out.write(shortToByteArray(1))
            out.write(shortToByteArray(channels.toShort()))
            out.write(intToByteArray(SAMPLE_RATE))
            out.write(intToByteArray(byteRate.toInt()))
            out.write(shortToByteArray((channels * 2).toShort()))
            out.write(shortToByteArray(16))

            out.write("data".toByteArray())
            out.write(intToByteArray(totalAudioLen.toInt()))
            out.write(pcmData)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }
}
