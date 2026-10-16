/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.usecase

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.rtbishop.look4sat.core.domain.usecase.ISaveImage
import com.rtbishop.look4sat.core.domain.usecase.SstvFileSaveResult
import com.rtbishop.look4sat.core.domain.usecase.SstvSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the picture and WAV independently. A failure in one operation must never remove
 * the other file or turn a successful save into a generic "image save failed" result.
 */
class SaveImage(private val context: Context) : ISaveImage {
    override suspend fun saveImageOnly(
        pixels: IntArray,
        width: Int,
        height: Int,
        modeName: String
    ): SstvFileSaveResult = withContext(Dispatchers.IO) {
        val safeModeName = modeName.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "SSTV" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "SSTV_${safeModeName}_$timestamp.png"
        var bitmap: Bitmap? = null
        try {
            require(width > 0 && height > 0 && pixels.size.toLong() >= width.toLong() * height.toLong()) {
                "invalid preview image dimensions or pixel buffer"
            }
            val valBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            bitmap = valBitmap
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                savePngWithMediaStore(valBitmap, filename)
            } else {
                savePngToExternalStorage(valBitmap, filename)
            }
            Log.i(TAG_IMAGE, "PNG saved: Pictures/Look4Sat/$filename")
            SstvFileSaveResult(saved = true, filename = filename, error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = describeFailure(e)
            Log.e(TAG_IMAGE, "PNG save failed ($filename): $message", e)
            SstvFileSaveResult(saved = false, filename = filename, error = message)
        } finally {
            bitmap?.recycle()
        }
    }

    override suspend fun saveAudioOnly(
        modeName: String,
        audioPcm16Chunks: List<ShortArray>,
        sampleRate: Int
    ): SstvFileSaveResult = withContext(Dispatchers.IO) {
        val safeModeName = modeName.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "SSTV" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val filename = "SSTV_${safeModeName}_$timestamp.wav"
        try {
            require(sampleRate > 0) { "invalid audio sample rate" }
            require(audioPcm16Chunks.any { it.isNotEmpty() }) { "no audio samples captured during decoding" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWavWithMediaStore(filename, audioPcm16Chunks, sampleRate)
            } else {
                saveWavToExternalStorage(filename, audioPcm16Chunks, sampleRate)
            }
            Log.i(TAG_WAV, "WAV saved: Recordings/L4SLABS/$filename, sampleRate=$sampleRate")
            SstvFileSaveResult(saved = true, filename = filename, error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = describeFailure(e)
            Log.e(TAG_WAV, "WAV save failed ($filename): $message", e)
            SstvFileSaveResult(saved = false, filename = filename, error = message)
        }
    }

    override suspend fun invoke(
        pixels: IntArray,
        width: Int,
        height: Int,
        modeName: String,
        audioPcm16Chunks: List<ShortArray>,
        sampleRate: Int
    ): SstvSaveResult = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val safeModeName = modeName.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "SSTV" }
        val baseName = "SSTV_${safeModeName}_$timestamp"
        val imageFilename = "$baseName.png"
        val wavFilename = "$baseName.wav"

        var imageSaved = false
        var wavSaved = false
        var imageError: String? = null
        var wavError: String? = null
        var bitmap: Bitmap? = null

        // Image saving: validate and save PNG, reporting image-only failures.
        try {
            require(width > 0 && height > 0 && pixels.size.toLong() >= width.toLong() * height.toLong()) {
                "invalid image dimensions or pixel buffer"
            }
            bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                savePngWithMediaStore(bitmap, imageFilename)
            } else {
                savePngToExternalStorage(bitmap, imageFilename)
            }
            imageSaved = true
            Log.i(TAG_IMAGE, "PNG saved: Pictures/Look4Sat/$imageFilename")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            imageError = describeFailure(e)
            Log.e(TAG_IMAGE, "PNG save failed ($imageFilename): $imageError", e)
        } finally {
            bitmap?.recycle()
        }

        // WAV saving is independent: it is attempted even if PNG creation failed.
        try {
            require(sampleRate > 0) { "invalid audio sample rate" }
            require(audioPcm16Chunks.any { it.isNotEmpty() }) {
                "no audio samples captured; start SSTV reception before saving"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWavWithMediaStore(wavFilename, audioPcm16Chunks, sampleRate)
            } else {
                saveWavToExternalStorage(wavFilename, audioPcm16Chunks, sampleRate)
            }
            wavSaved = true
            Log.i(TAG_WAV, "WAV saved: Recordings/L4SLABS/$wavFilename, sampleRate=$sampleRate")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            wavError = describeFailure(e)
            Log.e(TAG_WAV, "WAV save failed ($wavFilename): $wavError", e)
        }

        SstvSaveResult(
            imageSaved = imageSaved,
            wavSaved = wavSaved,
            imageError = imageError,
            wavError = wavError,
            imageFilename = imageFilename,
            wavFilename = wavFilename
        )
    }

    private fun savePngWithMediaStore(bitmap: Bitmap, filename: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        var uri: Uri? = null
        var published = false
        try {
            val currentUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("could not create PNG entry in MediaStore")
            uri = currentUri
            val output = resolver.openOutputStream(currentUri)
                ?: throw IOException("could not open PNG output stream")
            output.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("PNG encoder returned failure")
                }
            }
            val ready = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (resolver.update(currentUri, ready, null, null) != 1) {
                throw IOException("could not publish PNG to gallery")
            }
            published = true
        } finally {
            if (!published) uri?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }

    private fun saveWavWithMediaStore(filename: String, chunks: List<ShortArray>, sampleRate: Int) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, WAV_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        var uri: Uri? = null
        var published = false
        try {
            val currentUri = resolver.insert(collection, values)
                ?: throw IOException("could not create WAV entry in MediaStore")
            uri = currentUri
            val output = resolver.openOutputStream(currentUri)
                ?: throw IOException("could not open WAV output stream")
            output.use { writeWav(it, chunks, sampleRate) }
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(currentUri, ready, null, null) != 1) {
                throw IOException("could not publish WAV to storage")
            }
            published = true
        } finally {
            if (!published) uri?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }

    private fun picturesDirectory(): File {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val directory = File(pictures, "Look4Sat")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("could not create Pictures/Look4Sat directory")
        }
        return directory
    }

    private fun recordingsDirectory(): File {
        // Use the standard public Recordings directory. A literal is used for API compatibility.
        val recordings = Environment.getExternalStoragePublicDirectory("Recordings")
        val directory = File(recordings, "L4SLABS")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("could not create Recordings/L4SLABS directory")
        }
        return directory
    }

    private fun savePngToExternalStorage(bitmap: Bitmap, filename: String) {
        val file = File(picturesDirectory(), filename)
        var complete = false
        try {
            FileOutputStream(file).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("PNG encoder returned failure")
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            complete = true
        } finally {
            if (!complete && file.exists()) runCatching { file.delete() }
        }
    }

    private fun saveWavToExternalStorage(filename: String, chunks: List<ShortArray>, sampleRate: Int) {
        val file = File(recordingsDirectory(), filename)
        var complete = false
        try {
            FileOutputStream(file).use { writeWav(it, chunks, sampleRate) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("audio/wav"), null)
            complete = true
        } finally {
            // Only remove the incomplete WAV. Never delete an image already saved successfully.
            if (!complete && file.exists()) runCatching { file.delete() }
        }
    }

    /** Write standard mono 16-bit PCM RIFF/WAVE using the same samples fed to the SSTV decoder. */
    private fun writeWav(output: OutputStream, chunks: List<ShortArray>, sampleRate: Int) {
        val sampleCount = chunks.sumOf { it.size.toLong() }
        val dataBytesLong = sampleCount * 2L
        require(dataBytesLong <= Int.MAX_VALUE.toLong() - 36L) {
            "audio exceeds the 2 GB WAV format limit"
        }
        val dataBytes = dataBytesLong.toInt()
        val out = BufferedOutputStream(output, 64 * 1024)
        out.writeAscii("RIFF")
        out.writeLittleEndianInt(36 + dataBytes)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeLittleEndianInt(16)
        out.writeLittleEndianShort(1) // PCM
        out.writeLittleEndianShort(1) // mono
        out.writeLittleEndianInt(sampleRate)
        out.writeLittleEndianInt(sampleRate * 2)
        out.writeLittleEndianShort(2)
        out.writeLittleEndianShort(16)
        out.writeAscii("data")
        out.writeLittleEndianInt(dataBytes)
        chunks.forEach { chunk -> chunk.forEach { out.writeLittleEndianShort(it.toInt()) } }
        out.flush()
    }

    private fun OutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun OutputStream.writeLittleEndianShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun OutputStream.writeLittleEndianInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun describeFailure(error: Exception): String {
        val detail = error.message?.trim().orEmpty()
        return when (error) {
            is SecurityException -> "permission denied${if (detail.isBlank()) "" else ": $detail"}"
            is IOException -> detail.ifBlank { "storage I/O error" }
            else -> detail.ifBlank { error.javaClass.simpleName }
        }.take(MAX_ERROR_CHARS)
    }

    private companion object {
        const val RELATIVE_PATH = "Pictures/Look4Sat"
        const val WAV_RELATIVE_PATH = "Recordings/L4SLABS"
        const val TAG_IMAGE = "SSTVImageSave"
        const val TAG_WAV = "SSTVWavSave"
        const val MAX_ERROR_CHARS = 100
    }
}
