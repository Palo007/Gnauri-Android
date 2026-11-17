package com.aistudio.gnauri.bvpmlc

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native WAV registry (contract §5.5).
 *
 * The JS side never ships bytes: [MainActivity]'s file-picker callback records every picked
 * `*.wav` URI here (keyed by [normName] of its OpenableColumns.DISPLAY_NAME), best-effort
 * grabbing a persistable read permission. [NativeAudioEngine.loadSchedule] then decodes lazily,
 * keeping ONLY channel 0, linearly resampled to the engine sample rate (contract §4.5, §5.5).
 *
 * Thread-safety: [register]/[decode]/[hasWav]/[clear] can be called from the JS-bridge thread,
 * the engine command thread and the UI thread, so all map access is synchronized on [lock].
 */
object WavRegistry {

    private const val TAG = "WavRegistry"

    private val lock = Any()

    /** normName(DISPLAY_NAME) -> content Uri. */
    private val uris = HashMap<String, Uri>()

    /** Decode cache keyed by "normName@sampleRate" -> channel-0 FloatArray at that rate. */
    private val decoded = HashMap<String, FloatArray>()

    /**
     * normName: lowercase, strip path, trim. Must match JS index.html:722/804 semantics
     * (`String(s||"").trim().toLowerCase().replace(/^.*[\\/]/,"")`).
     */
    fun normName(s: String?): String {
        val trimmed = (s ?: "").trim().lowercase()
        val slash = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        return if (slash >= 0) trimmed.substring(slash + 1) else trimmed
    }

    /**
     * Record a picked WAV URI. Queries DISPLAY_NAME via OpenableColumns and, best-effort inside a
     * try/catch, takes a persistable read permission so the URI survives process death.
     */
    fun register(context: Context, uri: Uri) {
        val display = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: return
        val key = normName(display)
        if (key.isEmpty()) return
        synchronized(lock) {
            uris[key] = uri
            // decode cache is keyed by name; a re-registered name may point at a new file
            decoded.keys.removeAll { it.substringBeforeLast('@') == key }
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "takePersistableUriPermission failed for $uri: ${e.message}")
        }
    }

    /** Is there a registered URI for [name] (exact, then with a ".wav" suffix)? */
    fun hasWav(name: String): Boolean {
        val n = normName(name)
        synchronized(lock) {
            return uris.containsKey(n) || uris.containsKey("$n.wav")
        }
    }

    /** Returns a copy of the current URI map. */
    fun getUris(): Map<String, Uri> = synchronized(lock) { HashMap(uris) }

    /** Batch register URIs (for session restoration). */
    fun registerAll(context: Context, map: Map<String, Uri>) {
        synchronized(lock) {
            for ((key, uri) in map) {
                uris[key] = uri
                decoded.keys.removeAll { it.substringBeforeLast('@') == key }
            }
        }
        // Best-effort persistable permissions
        for (uri in map.values) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "takePersistableUriPermission failed for $uri: ${e.message}")
            }
        }
    }

    /** Clear both maps (contract §5.5 clear). */
    fun clear() {
        synchronized(lock) {
            uris.clear()
            decoded.clear()
        }
    }

    private fun lookupUri(name: String): Uri? {
        val n = normName(name)
        synchronized(lock) {
            uris[n]?.let { return it }
            uris["$n.wav"]?.let { return it }
            return null
        }
    }

    /**
     * Decode the WAV registered for [name] to a channel-0 FloatArray resampled to [sampleRate].
     * RIFF-PCM parser (int 8-unsigned / 16 / 24 / 32 LE + IEEE float32, mono or multi-channel,
     * keep channel 0) with a MediaExtractor/MediaCodec fallback for anything else. Cached per
     * (name, sampleRate). Returns null if no URI is registered or decoding fails.
     */
    fun decode(context: Context, name: String, sampleRate: Int): FloatArray? {
        val n = normName(name)
        val realKey = when {
            hasKey(n) -> n
            hasKey("$n.wav") -> "$n.wav"
            else -> return null
        }
        val cacheKey = "$realKey@$sampleRate"
        synchronized(lock) { decoded[cacheKey]?.let { return it } }

        val uri = lookupUri(realKey) ?: return null
        val result = try {
            decodeRiff(context, uri, sampleRate) ?: decodeViaCodec(context, uri, sampleRate)
        } catch (e: Exception) {
            Log.w(TAG, "decode($name) failed: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "decode($name) out of memory: ${e.message}")
            null
        }
        if (result != null) synchronized(lock) { decoded[cacheKey] = result }
        return result
    }

    private fun hasKey(key: String): Boolean = synchronized(lock) { uris.containsKey(key) }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryDisplayName failed for $uri: ${e.message}")
            null
        }
    }

    // ================================================================================
    // RIFF-PCM parser
    // ================================================================================

    /**
     * Parse a RIFF/WAVE file (fmt + data chunks). Supports PCM int 8-unsigned/16/24/32 LE and
     * IEEE float32; any channel count / rate. Keeps channel 0 only, then linear-resamples to
     * [targetRate]. Returns null if this is not a plain RIFF-PCM/float WAV (caller then falls
     * back to MediaCodec).
     */
    private fun decodeRiff(context: Context, uri: Uri, targetRate: Int): FloatArray? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size < 12) return null

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // "RIFF" .... "WAVE"
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null
        if (bytes[8] != 'W'.code.toByte() || bytes[9] != 'A'.code.toByte() ||
            bytes[10] != 'V'.code.toByte() || bytes[11] != 'E'.code.toByte()) return null

        var audioFormat = 0        // 1 = PCM int, 3 = IEEE float, 0xFFFE = extensible
        var channels = 0
        var srcRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLen = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id0 = bytes[pos]; val id1 = bytes[pos + 1]; val id2 = bytes[pos + 2]; val id3 = bytes[pos + 3]
            bb.position(pos + 4)
            val chunkSize = bb.int
            if (chunkSize < 0) break
            val body = pos + 8
            if (id0 == 'f'.code.toByte() && id1 == 'm'.code.toByte() &&
                id2 == 't'.code.toByte() && id3 == ' '.code.toByte()
            ) {
                if (body + 16 > bytes.size) return null
                bb.position(body)
                audioFormat = bb.short.toInt() and 0xFFFF
                channels = bb.short.toInt() and 0xFFFF
                srcRate = bb.int
                bb.int                 // byteRate
                bb.short               // blockAlign
                bitsPerSample = bb.short.toInt() and 0xFFFF
                if (audioFormat == 0xFFFE && chunkSize >= 40 && body + 40 <= bytes.size) {
                    // WAVE_FORMAT_EXTENSIBLE: real format is in the sub-format GUID's first 2 bytes
                    bb.position(body + 24)
                    audioFormat = bb.short.toInt() and 0xFFFF
                }
            } else if (id0 == 'd'.code.toByte() && id1 == 'a'.code.toByte() &&
                id2 == 't'.code.toByte() && id3 == 'a'.code.toByte()
            ) {
                dataOffset = body
                dataLen = minOf(chunkSize, bytes.size - body)
            }
            // chunks are word-aligned (pad byte if odd size)
            pos = body + chunkSize + (chunkSize and 1)
        }

        if (channels <= 0 || srcRate <= 0 || dataOffset < 0 || dataLen <= 0) return null
        val isFloat = audioFormat == 3
        val isPcm = audioFormat == 1
        if (!isFloat && !isPcm) return null

        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) return null
        val frameBytes = bytesPerSample * channels
        if (frameBytes <= 0) return null
        val frameCount = dataLen / frameBytes
        if (frameCount <= 0) return null

        val ch0 = FloatArray(frameCount)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        var d = dataOffset
        var fi = 0
        while (fi < frameCount) {
            // read channel-0 sample; then skip the remaining channels' bytes
            val v: Float = when {
                isFloat && bitsPerSample == 32 -> {
                    bb.position(d); bb.float
                }
                isPcm && bitsPerSample == 8 -> {
                    // 8-bit PCM is UNSIGNED (0..255, midpoint 128)
                    ((bytes[d].toInt() and 0xFF) - 128) / 128.0f
                }
                isPcm && bitsPerSample == 16 -> {
                    bb.position(d); bb.short / 32768.0f
                }
                isPcm && bitsPerSample == 24 -> {
                    val b0 = bytes[d].toInt() and 0xFF
                    val b1 = bytes[d + 1].toInt() and 0xFF
                    val b2 = bytes[d + 2].toInt() and 0xFF
                    var s = (b2 shl 16) or (b1 shl 8) or b0
                    if (s and 0x800000 != 0) s = s or -0x1000000 // sign-extend
                    s / 8388608.0f
                }
                isPcm && bitsPerSample == 32 -> {
                    bb.position(d); bb.int / 2147483648.0f
                }
                else -> return null // unsupported bit depth -> caller falls back to MediaCodec
            }
            ch0[fi] = v
            d += frameBytes
            fi++
        }

        return resampleLinear(ch0, srcRate, targetRate)
    }

    // ================================================================================
    // MediaExtractor/MediaCodec fallback (compressed or exotic containers)
    // ================================================================================

    private fun decodeViaCodec(context: Context, uri: Uri, targetRate: Int): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: return null

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else targetRate
            val channels = (if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1).coerceAtLeast(1)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var out = FloatArray(srcRate.coerceAtLeast(1) * 4)
            var outCount = 0
            var sawInputEos = false
            var sawOutputEos = false
            val timeoutUs = 10_000L

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        // MediaCodec PCM output is 16-bit signed LE interleaved
                        val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val totalSamples = sb.remaining()
                        var s = 0
                        while (s < totalSamples) {
                            // keep channel 0 only
                            if (outCount == out.size) out = out.copyOf(out.size * 2)
                            out[outCount++] = sb.get(s) / 32768.0f
                            s += channels
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // channel count may be updated here on some decoders; ignore (we used input format)
                }
            }

            if (outCount == 0) return null
            val ch0 = out.copyOf(outCount)
            return resampleLinear(ch0, srcRate, targetRate)
        } catch (e: Exception) {
            Log.w(TAG, "decodeViaCodec failed: ${e.message}")
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    // ================================================================================
    // Resampling
    // ================================================================================

    /** Linear resample [src] from [srcRate] to [dstRate]. Identity when rates match. */
    private fun resampleLinear(src: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || src.isEmpty()) return src
        val ratio = dstRate.toDouble() / srcRate.toDouble()
        val outLen = (src.size * ratio).toInt().coerceAtLeast(1)
        val dst = FloatArray(outLen)
        val step = srcRate.toDouble() / dstRate.toDouble()
        var srcPos = 0.0
        var i = 0
        val lastIndex = src.size - 1
        while (i < outLen) {
            val i0 = srcPos.toInt()
            if (i0 >= lastIndex) {
                dst[i] = src[lastIndex]
            } else {
                val frac = (srcPos - i0).toFloat()
                dst[i] = src[i0] + (src[i0 + 1] - src[i0]) * frac
            }
            srcPos += step
            i++
        }
        return dst
    }
}
