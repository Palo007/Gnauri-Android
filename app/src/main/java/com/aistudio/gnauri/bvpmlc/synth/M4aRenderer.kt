package com.aistudio.gnauri.bvpmlc.synth

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aistudio.gnauri.bvpmlc.NativeAudioEvents
import com.aistudio.gnauri.bvpmlc.WavRegistry
import com.aistudio.gnauri.bvpmlc.WebViewManager
import java.io.File
import kotlin.math.min

object M4aRenderer {
    private const val TAG = "M4aRenderer"

    fun render(
        context: Context,
        scheduleJson: String,
        durationSec: Double,
        uiVol: Double,
        chL: Double,
        chR: Double,
        fadeOut30s: Boolean,
        filename: String
    ) {
        Thread {
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var tempFile: File? = null
            var muxerStarted = false

            try {
                val schedule = Schedule.fromJson(scheduleJson) ?: throw Exception("Invalid schedule JSON")
                val sampleRate = 44100 // Standard CD sample rate for maximum AAC compatibility
                val bitRate = 128000  // 128 kbps as selected by the user
                val channelCount = 2  // Stereo output

                // 1. Setup offline synth engine
                val offlineSynth = SynthEngine(sampleRate)
                offlineSynth.setMasterVolume(uiVol)
                offlineSynth.setChannelGains(chL, chR)
                offlineSynth.setFadeOut30s(fadeOut30s)

                // Decode and preload type-2 wav buffers
                val buffers = ArrayList<Pair<String, FloatArray>>()
                for (v in schedule.voices) {
                    if (v.type != 2) continue
                    val candidates = buildList {
                        if (v.desc.isNotBlank()) { add(v.desc); add(v.desc + ".wav") }
                        v.file?.let { if (it.isNotBlank()) add(it) }
                    }
                    for (c in candidates) {
                        val d = WavRegistry.decode(context, c, sampleRate)
                        if (d != null) {
                            buffers.add(c to d)
                            break
                        }
                    }
                }
                for ((name, buf) in buffers) {
                    offlineSynth.setAudioBuffer(name, buf)
                }

                // Force infinite loop for the offline synth so we can render exactly up to durationSec
                val renderSchedule = schedule.copy(loops = 0)
                offlineSynth.setSchedule(renderSchedule)
                offlineSynth.play(0.0)

                // 2. Setup encoder & muxer
                tempFile = File.createTempFile("render_", ".m4a", context.cacheDir)
                muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)

                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()

                var totalSamplesNeeded = (durationSec * sampleRate).toLong()
                var samplesRendered = 0L
                var inputEOS = false
                var outputEOS = false
                var audioTrackIndex = -1
                val timeoutUs = 10_000L

                val chunkSize = 16384
                val renderBuffer = FloatArray(chunkSize * channelCount)
                val conversionBuffer = ShortArray(chunkSize * channelCount)

                var lastProgressTime = 0L

                while (!outputEOS) {
                    // Feed Input to Encoder
                    if (!inputEOS) {
                        val inputBufferIndex = codec.dequeueInputBuffer(timeoutUs)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                            inputBuffer.clear()

                            val maxFramesToRender = inputBuffer.remaining() / (channelCount * 2)
                            val framesRemaining = (totalSamplesNeeded - samplesRendered).toInt()
                            val framesToRender = minOf(maxFramesToRender, framesRemaining)

                            if (framesToRender <= 0) {
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEOS = true
                            } else {
                                val renderFrames = minOf(framesToRender, chunkSize)
                                offlineSynth.render(renderBuffer, renderFrames)

                                // Apply manual studio-grade linear fadeout to the last 30 seconds of the requested duration
                                if (fadeOut30s) {
                                    val fadeSamples = min(30L * sampleRate, totalSamplesNeeded)
                                    val fadeStartSample = totalSamplesNeeded - fadeSamples
                                    for (f in 0 until renderFrames) {
                                        val sampleIdx = samplesRendered + f
                                        if (sampleIdx >= fadeStartSample) {
                                            val frac = (1.0f - (sampleIdx - fadeStartSample).toFloat() / fadeSamples.toFloat()).coerceIn(0.0f, 1.0f)
                                            renderBuffer[f * 2] *= frac
                                            renderBuffer[f * 2 + 1] *= frac
                                        }
                                    }
                                }

                                val shortBuffer = inputBuffer.asShortBuffer()
                                val count = renderFrames * channelCount
                                for (j in 0 until count) {
                                    val fVal = renderBuffer[j]
                                    conversionBuffer[j] = (fVal * 32767.0f).coerceIn(-32768f, 32767f).toInt().toShort()
                                }
                                shortBuffer.put(conversionBuffer, 0, count)
                                inputBuffer.position(count * 2)

                                val presentationTimeUs = (samplesRendered * 1_000_000L) / sampleRate
                                codec.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    count * 2,
                                    presentationTimeUs,
                                    0
                                )

                                samplesRendered += renderFrames

                                val now = System.currentTimeMillis()
                                if (now - lastProgressTime > 150L) {
                                    val progress = samplesRendered.toDouble() / totalSamplesNeeded.toDouble()
                                    NativeAudioEvents.pushRenderProgress(progress, false, null)
                                    lastProgressTime = now
                                }
                            }
                        }
                    }

                    // Retrieve Output from Encoder and Mux to file
                    val info = MediaCodec.BufferInfo()
                    var outputBufferIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                    while (outputBufferIndex >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxerStarted) {
                            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)!!
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            muxer.writeSampleData(audioTrackIndex, outputBuffer, info)
                        }
                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEOS = true
                            break
                        }
                        outputBufferIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                    }

                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = codec.outputFormat
                        audioTrackIndex = muxer.addTrack(newFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                // 3. Complete and trigger native document saving
                codec.stop()
                codec.release()
                codec = null

                if (muxerStarted) {
                    muxer.stop()
                }
                muxer.release()
                muxer = null

                val m4aBytes = tempFile.readBytes()
                WebViewManager.exportBinaryData = m4aBytes

                Handler(Looper.getMainLooper()).post {
                    WebViewManager.createDocumentLauncher?.launch(filename)
                }

                NativeAudioEvents.pushRenderProgress(1.0, true, null)

            } catch (e: Exception) {
                Log.e(TAG, "M4A rendering failed", e)
                NativeAudioEvents.pushRenderProgress(0.0, true, e.message ?: "Unknown encoding error")
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "M4A rendering ran out of memory", e)
                NativeAudioEvents.pushRenderProgress(0.0, true, "Out of memory while rendering")
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                try { tempFile?.delete() } catch (_: Exception) {}
            }
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }
}
