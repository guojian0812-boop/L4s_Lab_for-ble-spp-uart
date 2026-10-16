/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.domain.usecase

/** Independent save outcomes so a PNG failure cannot hide a WAV failure (or vice versa). */
data class SstvSaveResult(
    val imageSaved: Boolean,
    val wavSaved: Boolean,
    val imageError: String?,
    val wavError: String?,
    val imageFilename: String,
    val wavFilename: String
)


/**
 * Saves a decoded SSTV image and matching WAV recording independently; each result includes its own failure reason.
 * The caller must obtain storage permission on Android API 24-28 before saving.
 * @return separate image and WAV success flags and error details
 */
/** Result for a single SSTV file save, with a user-displayable error on failure. */
data class SstvFileSaveResult(
    val saved: Boolean,
    val filename: String,
    val error: String?
)

interface ISaveImage {
    suspend operator fun invoke(
        pixels: IntArray,
        width: Int,
        height: Int,
        modeName: String,
        audioPcm16Chunks: List<ShortArray>,
        sampleRate: Int
    ): SstvSaveResult

    /** Save only the current SSTV preview/full-frame PNG. Does not write audio. */
    suspend fun saveImageOnly(
        pixels: IntArray,
        width: Int,
        height: Int,
        modeName: String
    ): SstvFileSaveResult

    /** Save only the audio collected during the current decode session as WAV. */
    suspend fun saveAudioOnly(
        modeName: String,
        audioPcm16Chunks: List<ShortArray>,
        sampleRate: Int
    ): SstvFileSaveResult
}
