package com.asrcodes.dontmissmom

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.CountDownTimer
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

class VoiceRecorderHelper(
    private val context: Context,
    private val onCountdownTick: (secondsLeft: Int) -> Unit,
    private val onRecordingComplete: () -> Unit,
    private val onUploadSuccess: (url: String) -> Unit,
    private val onError: (message: String) -> Unit
) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var countdownTimer: CountDownTimer? = null
    @Volatile private var released = false
    @Volatile private var uploadConnection: HttpURLConnection? = null

    companion object {
        // ─────────────────────────────────────────────────────────
        //  Fill these in from your Cloudinary Dashboard:
        //  cloudinary.com → Dashboard → Cloud name
        //  cloudinary.com → Settings → Upload → Upload presets
        //  Create one preset, set mode = "Unsigned", copy name
        // ─────────────────────────────────────────────────────────
        private const val CLOUD_NAME    = "dqrb7jasx"      // e.g. "dontmissmom"
        private const val UPLOAD_PRESET = "DontMissMom_preset"   // e.g. "sos_unsigned"
        // ─────────────────────────────────────────────────────────
    }

    // ────────────────────────────────────────────
    //  START — kicks off recording + 5s countdown
    // ────────────────────────────────────────────
    fun startRecording() {
        released = false
        try {
            val file = File(context.cacheDir, "sos_voice_${System.currentTimeMillis()}.3gp")
            outputFile = file

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            countdownTimer = object : CountDownTimer(5000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    if (!released) onCountdownTick(((millisUntilFinished + 999) / 1000).toInt())
                }
                override fun onFinish() {
                    stopAndUpload()
                }
            }.start()

        } catch (e: Exception) {
            if (!released) onError("Could not start recording")
            release()
        }
    }

    // ────────────────────────────────────────────
    //  STOP — finishes recording, triggers upload
    // ────────────────────────────────────────────
    private fun stopAndUpload() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            if (!released) onError("Recording failed")
            release()
            return
        }

        if (released) return
        onRecordingComplete()

        val file = outputFile ?: run {
            onError("Recording file not found")
            return
        }

        Thread { uploadToCloudinary(file) }.start()
    }

    // ────────────────────────────────────────────
    //  UPLOAD — multipart POST to Cloudinary
    //  Uses "unsigned" preset — no API secret
    //  needed in the app. Safe for mobile.
    // ────────────────────────────────────────────
    private fun uploadToCloudinary(file: File) {
        val boundary  = "----DMM${System.currentTimeMillis()}"
        val uploadUrl = "https://api.cloudinary.com/v1_1/$CLOUD_NAME/raw/upload"

        try {
            val conn = URL(uploadUrl).openConnection() as HttpURLConnection
            uploadConnection = conn
            if (released) return
            conn.requestMethod = "POST"
            conn.doOutput      = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.connectTimeout = 15_000
            conn.readTimeout    = 15_000

            DataOutputStream(conn.outputStream).use { dos ->

                // Field: upload_preset
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"upload_preset\"\r\n\r\n")
                dos.writeBytes("$UPLOAD_PRESET\r\n")

                // Field: resource_type = raw (required for non-image files)
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"resource_type\"\r\n\r\n")
                dos.writeBytes("raw\r\n")

                // Field: folder (keeps Cloudinary dashboard organised)
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes("Content-Disposition: form-data; name=\"folder\"\r\n\r\n")
                dos.writeBytes("sos_voice_memos\r\n")

                // Field: file (the actual audio bytes)
                dos.writeBytes("--$boundary\r\n")
                dos.writeBytes(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n"
                )
                dos.writeBytes("Content-Type: audio/3gpp\r\n\r\n")
                FileInputStream(file).use { it.copyTo(dos) }
                dos.writeBytes("\r\n")

                // Closing boundary
                dos.writeBytes("--$boundary--\r\n")
                dos.flush()
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val json      = JSONObject(conn.inputStream.bufferedReader().readText())
                val secureUrl = json.getString("secure_url")
                if (IncomingAlert.validVoiceUrl(secureUrl)) {
                    // Register even if the recording was cancelled during upload, so unused assets expire.
                    android.os.Handler(android.os.Looper.getMainLooper()).post { VoiceRegistrationQueue.add(context.applicationContext, secureUrl) }
                }
                file.delete()
                if (!released && IncomingAlert.validVoiceUrl(secureUrl)) onUploadSuccess(secureUrl)
                else if (!released) onError("Invalid upload response")
            } else {
                file.delete()
                if (!released) onError("Upload failed")
            }

        } catch (e: Exception) {
            file.delete()
            if (!released) onError("Upload failed")
        } finally {
            uploadConnection?.disconnect()
            uploadConnection = null
            file.delete()
        }
    }

    // ────────────────────────────────────────────
    //  RELEASE — call from onDestroy or on cancel
    // ────────────────────────────────────────────
    fun release() {
        released = true
        uploadConnection?.disconnect()
        countdownTimer?.cancel()
        countdownTimer = null
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null
        outputFile?.delete()
        outputFile = null
    }
}
