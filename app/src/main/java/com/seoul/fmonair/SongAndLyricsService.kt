package com.seoul.fmonair

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object SongAndLyricsService {

    private const val PREFS_HISTORY = "seoul_fm_song_history"
    private const val KEY_HISTORY_JSON = "saved_songs_json"
    private const val KEY_AUDD_TOKEN = "audd_api_token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val hangulRegex = Regex("[가-힣]")

    fun getAuddApiToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AUDD_TOKEN, "test") ?: "test"
    }

    fun saveAuddApiToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AUDD_TOKEN, token.trim().ifEmpty { "test" }).apply()
    }

    /**
     * 영문/로마자로 인식된 한국 곡의 제목과 가수를 한국어 공식 명칭으로 변환합니다.
     * 1순위: Apple Music / iTunes Korea (country=kr&lang=ko_kr) ID 정확 매칭
     * 2순위: iTunes Korea 검색 매칭
     * 3순위: 멜론(Melon) 실시간 검색 키워드 API 매칭
     */
    suspend fun localizeTrackToKorean(
        rawTitle: String,
        rawArtist: String,
        appleMusicTrackId: String = ""
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        var bestTitle = rawTitle.trim()
        var bestArtist = rawArtist.trim()

        // 이미 제목과 가수 모두 한글이 포함되어 있다면 그대로 사용
        if (hangulRegex.containsMatchIn(bestTitle) && hangulRegex.containsMatchIn(bestArtist)) {
            return@withContext bestTitle to bestArtist
        }

        // 1단계: AudD에서 받은 Apple Music Track ID로 한국 스토어(ko_kr) 직접 조회
        if (appleMusicTrackId.isNotBlank()) {
            try {
                val lookupUrl = "https://itunes.apple.com/lookup?id=${URLEncoder.encode(appleMusicTrackId, "UTF-8")}&country=kr&lang=ko_kr"
                val req = Request.Builder()
                    .url(lookupUrl)
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; ko-KR)")
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.startsWith("{")) {
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val item = results.getJSONObject(0)
                            val krTitle = item.optString("trackName", "").trim()
                            val krArtist = item.optString("artistName", "").trim()
                            if (krTitle.isNotBlank()) bestTitle = krTitle
                            if (krArtist.isNotBlank()) bestArtist = krArtist
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 2단계: 여전히 영문인 경우 iTunes 한국 스토어 검색으로 한글 곡명/가수명 조회
        if (!hangulRegex.containsMatchIn(bestTitle) || !hangulRegex.containsMatchIn(bestArtist)) {
            try {
                val cleanTitle = bestTitle.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim()
                val query = "$bestArtist $cleanTitle".trim()
                val searchUrl = "https://itunes.apple.com/search?term=${URLEncoder.encode(query, "UTF-8")}&country=kr&lang=ko_kr&entity=song&limit=5"
                val req = Request.Builder()
                    .url(searchUrl)
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; ko-KR)")
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.startsWith("{")) {
                        val json = JSONObject(body)
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            // 한글이 포함된 결과를 최우선 선택
                            for (i in 0 until results.length()) {
                                val item = results.getJSONObject(i)
                                val candTitle = item.optString("trackName", "").trim()
                                val candArtist = item.optString("artistName", "").trim()
                                if (hangulRegex.containsMatchIn(candTitle) || hangulRegex.containsMatchIn(candArtist)) {
                                    if (candTitle.isNotBlank()) bestTitle = candTitle
                                    if (candArtist.isNotBlank()) bestArtist = candArtist
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3단계: 멜론(Melon) 키워드 검색 API를 통해 한국어 곡명/가수명 최종 보정
        try {
            val cleanQuery = "${bestArtist.split("(").first().trim()} ${bestTitle.split("(").first().trim()}".trim()
            val melonUrl = "https://www.melon.com/search/keyword/index.json?query=${URLEncoder.encode(cleanQuery, "UTF-8")}"
            val req = Request.Builder()
                .url(melonUrl)
                .header("Referer", "https://www.melon.com/")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; ko-KR)")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.trim().orEmpty()
                if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    val songs = json.optJSONArray("SONGCONTENTS")
                    if (songs != null && songs.length() > 0) {
                        val topSong = songs.getJSONObject(0)
                        val melonTitle = topSong.optString("SONGNAME", "").trim()
                        val melonArtist = topSong.optString("ARTISTNAME", "").trim()
                        if (melonTitle.isNotBlank() && hangulRegex.containsMatchIn(melonTitle)) {
                            bestTitle = melonTitle
                        }
                        if (melonArtist.isNotBlank() && hangulRegex.containsMatchIn(melonArtist)) {
                            bestArtist = melonArtist
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return@withContext bestTitle to bestArtist
    }

    suspend fun searchLyricsFromLrcLib(
        query: String,
        stationName: String = "실시간 검색",
        frequencyText: String = "LRCLIB"
    ): List<RecognizedSong> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val results = mutableListOf<RecognizedSong>()
        try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://lrclib.net/api/search?q=$encoded")
                .header("User-Agent", "SeoulFMRadioApp/2.2 (https://github.com/seoul-fm-radio)")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("[")) {
                    val arr = JSONArray(body)
                    val limit = minOf(arr.length(), 12)
                    for (i in 0 until limit) {
                        val obj = arr.getJSONObject(i)
                        val title = obj.optString("trackName", "제목 미상")
                        val artist = obj.optString("artistName", "아티스트 미상")
                        val album = obj.optString("albumName", "")
                        val plain = obj.optString("plainLyrics", "")
                        val syncedRaw = obj.optString("syncedLyrics", "")
                        val parsedSynced = parseLrcText(syncedRaw, plain)

                        val fallbackPlain = if (plain.isNotBlank()) {
                            plain
                        } else {
                            parsedSynced.joinToString(separator = "\n") { it.text }
                        }

                        results.add(
                            RecognizedSong(
                                id = UUID.randomUUID().toString(),
                                title = title,
                                artist = artist,
                                album = album,
                                stationName = stationName,
                                frequencyText = frequencyText,
                                recognizedAt = nowTimeString(),
                                plainLyrics = fallbackPlain,
                                syncedLyrics = parsedSynced,
                                sourceMethod = "LRCLIB 실시간 가사 API"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext results
    }

    /**
     * 정밀 하이브리드 곡 인식 엔진 (한국어 곡명/가수 자동 변환 지원):
     * 1순위: 실시간 HLS/ICY 스트림 메타데이터
     * 2순위: 방송사별 실시간 선곡표/온에어 API 직접 호출
     * 3순위: 실제 오디오 5초 녹음(WAV) 후 AudD 음원 인식 + 한국어 곡명/가수 자동 변환
     */
    suspend fun recognizeCurrentRadioSong(
        context: Context,
        station: RadioStation,
        icyMetadataTitle: String? = null
    ): RecognizedSong = withContext(Dispatchers.IO) {
        var detectedTitle = ""
        var detectedArtist = ""
        var appleMusicId = ""
        var methodLabel = ""

        // [1단계] 스트림 내부 실시간 ICY/HLS 태그 확인
        if (!icyMetadataTitle.isNullOrBlank()) {
            val cleaned = icyMetadataTitle.trim()
            if (cleaned.contains(" - ")) {
                val parts = cleaned.split(" - ", limit = 2)
                detectedArtist = parts[0].trim()
                detectedTitle = parts[1].trim()
                methodLabel = "실시간 스트림 메타데이터"
            } else if (cleaned.contains("-")) {
                val parts = cleaned.split("-", limit = 2)
                detectedArtist = parts[0].trim()
                detectedTitle = parts[1].trim()
                methodLabel = "실시간 스트림 메타데이터"
            }
        }

        // [2단계] 방송사 실시간 온에어/선곡 API 조회
        if (detectedTitle.isBlank()) {
            val stationMeta = fetchBroadcasterRealtimeSong(station)
            if (stationMeta != null) {
                detectedTitle = stationMeta.first
                detectedArtist = stationMeta.second
                methodLabel = "${station.broadcaster} 실시간 선곡 정보"
            }
        }

        // [3단계] 실제 마이크/오디오 출력 5초 샘플링 WAV 캡처 후 AudD API 전송
        if (detectedTitle.isBlank()) {
            val wavBytes = record5SecondsWavAudio()
            if (wavBytes != null && wavBytes.size > 1000) {
                val apiToken = getAuddApiToken(context)
                try {
                    val multipart = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("api_token", apiToken)
                        .addFormDataPart("return", "lyrics,apple_music,spotify")
                        .addFormDataPart(
                            "file",
                            "radio_live_sample.wav",
                            wavBytes.toRequestBody("audio/wav".toMediaTypeOrNull())
                        )
                        .build()

                    val request = Request.Builder()
                        .url("https://api.audd.io/")
                        .post(multipart)
                        .build()

                    client.newCall(request).execute().use { response ->
                        val jsonStr = response.body?.string().orEmpty()
                        if (jsonStr.startsWith("{")) {
                            val root = JSONObject(jsonStr)
                            val resultObj = root.optJSONObject("result")
                            if (resultObj != null) {
                                detectedTitle = resultObj.optString("title", "")
                                detectedArtist = resultObj.optString("artist", "")
                                val appleObj = resultObj.optJSONObject("apple_music")
                                appleMusicId = appleObj?.optString("id", "").orEmpty()
                                if (detectedTitle.isNotBlank()) {
                                    methodLabel = "5초 오디오 인식 (한국어 변환)"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                delay(2000)
            }
        }

        // [4단계] 영문으로 인식된 한국 노래를 한국어 제목·가수로 자동 변환 후 가사 조회
        if (detectedTitle.isNotBlank()) {
            val rawEnTitle = detectedTitle
            val rawEnArtist = detectedArtist

            val (korTitle, korArtist) = localizeTrackToKorean(
                rawTitle = rawEnTitle,
                rawArtist = rawEnArtist,
                appleMusicTrackId = appleMusicId
            )

            detectedTitle = korTitle
            detectedArtist = korArtist

            // 1차: 변환된 한국어 제목 + 가수로 가사 검색
            var lrcMatches = searchLyricsFromLrcLib(
                query = "$detectedTitle $detectedArtist".trim(),
                stationName = station.name,
                frequencyText = station.frequencyText
            )

            // 2차: 한국어 검색 결과가 없으면 원본 영문 제목으로 가사 재검색하되 표시는 한국어 제목 유지
            if (lrcMatches.isEmpty() && (rawEnTitle != detectedTitle || rawEnArtist != detectedArtist)) {
                lrcMatches = searchLyricsFromLrcLib(
                    query = "$rawEnTitle $rawEnArtist".trim(),
                    stationName = station.name,
                    frequencyText = station.frequencyText
                )
            }

            if (lrcMatches.isNotEmpty()) {
                val best = lrcMatches.first()
                return@withContext best.copy(
                    // 사용자가 멜론 등에서 바로 검색할 수 있도록 한국어 제목·가수를 최우선 유지
                    title = detectedTitle,
                    artist = detectedArtist,
                    stationName = station.name,
                    frequencyText = station.frequencyText,
                    recognizedAt = nowTimeString(),
                    sourceMethod = methodLabel
                )
            }

            val guideLines = listOf(
                LyricLine(0f, "🎵 인식된 곡: $detectedArtist - $detectedTitle"),
                LyricLine(3.5f, "🍈 바로 위 [멜론에서 검색·듣기] 버튼을 누르면 멜론에서 곡과 가사를 바로 볼 수 있습니다."),
                LyricLine(7.0f, "채널: ${station.name} (${station.frequencyText})"),
                LyricLine(10.5f, "방식: $methodLabel")
            )
            return@withContext RecognizedSong(
                id = UUID.randomUUID().toString(),
                title = detectedTitle,
                artist = detectedArtist,
                album = "${station.name} ON-AIR",
                stationName = station.name,
                frequencyText = station.frequencyText,
                recognizedAt = nowTimeString(),
                plainLyrics = guideLines.joinToString(separator = "\n") { it.text },
                syncedLyrics = guideLines,
                sourceMethod = methodLabel
            )
        }

        val noticeLines = listOf(
            LyricLine(0f, "🎙️ 현재 DJ 멘트/광고 중이거나 음원이 감지되지 않았습니다."),
            LyricLine(3.5f, "1) 노래가 선명하게 나올 때 스피커 볼륨을 높여 [BGM 인식]을 눌러주세요."),
            LyricLine(7.0f, "2) 또는 [🍈 멜론 로그인·검색] 버튼을 눌러 들리는 가사나 곡명을 멜론에서 바로 찾아보세요."),
            LyricLine(10.5f, "3) 방송국 공식 선곡표 버튼을 눌러도 실시간 선곡을 확인할 수 있습니다.")
        )
        return@withContext RecognizedSong(
            id = UUID.randomUUID().toString(),
            title = "현재 곡 미감지 (멘트/광고 또는 볼륨 부족)",
            artist = station.name,
            album = station.defaultProgram,
            stationName = station.name,
            frequencyText = station.frequencyText,
            recognizedAt = nowTimeString(),
            plainLyrics = noticeLines.joinToString(separator = "\n") { it.text },
            syncedLyrics = noticeLines,
            sourceMethod = "음원 신호 대기 중"
        )
    }

    private fun fetchBroadcasterRealtimeSong(station: RadioStation): Pair<String, String>? {
        try {
            when (station.broadcaster) {
                "KBS" -> {
                    val req = Request.Builder()
                        .url(station.endpointOrStreamUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            val items = json.optJSONArray("channel_item")
                            if (items != null && items.length() > 0) {
                                for (i in 0 until items.length()) {
                                    val obj = items.getJSONObject(i)
                                    val song = obj.optString("song_title", "").trim()
                                    val artist = obj.optString("artist_name", "").trim()
                                    if (song.isNotEmpty()) {
                                        return song to artist.ifEmpty { "KBS On-Air" }
                                    }
                                }
                            }
                        }
                    }
                }
                "MBC" -> {
                    val req = Request.Builder()
                        .url("https://miniapi.imbc.com/BroadCast/OnAirList")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (body.contains("SomItem") || body.contains("SongTitle")) {
                            val json = JSONObject(body)
                            val song = json.optString("SongTitle", "").trim()
                            val singer = json.optString("Singer", "").trim()
                            if (song.isNotEmpty()) return song to singer
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun record5SecondsWavAudio(): ByteArray? {
        val sampleRate = 8000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) return null

        var recorder: AudioRecord? = null
        return try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return null
            }

            recorder.startRecording()
            val pcmOut = ByteArrayOutputStream()
            val buffer = ByteArray(minBuf)
            val endTime = System.currentTimeMillis() + 4500L

            while (System.currentTimeMillis() < endTime) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcmOut.write(buffer, 0, read)
                }
            }
            recorder.stop()
            recorder.release()
            recorder = null

            val pcmBytes = pcmOut.toByteArray()
            if (pcmBytes.isEmpty()) null else encodePcmToWav(pcmBytes, sampleRate, 1, 16)
        } catch (e: Exception) {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun encodePcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcmData.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArrayOutputStream(44 + pcmData.size)
        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    fun parseLrcText(syncedLrc: String, plainLyrics: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        if (syncedLrc.isNotBlank()) {
            syncedLrc.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.startsWith("[") && line.contains("]")) {
                    val closeIdx = line.indexOf(']')
                    val timeTag = line.substring(1, closeIdx)
                    val lyricText = line.substring(closeIdx + 1).trim()
                    val colonIdx = timeTag.indexOf(':')
                    if (colonIdx > 0 && lyricText.isNotEmpty()) {
                        val minVal = timeTag.substring(0, colonIdx).toFloatOrNull() ?: 0f
                        val secVal = timeTag.substring(colonIdx + 1).toFloatOrNull() ?: 0f
                        lines.add(LyricLine(minVal * 60f + secVal, lyricText))
                    }
                }
            }
        }

        if (lines.isEmpty() && plainLyrics.isNotBlank()) {
            plainLyrics.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { idx, text ->
                    lines.add(LyricLine(idx * 3.5f, text))
                }
        }

        return lines
    }

    fun loadSavedSongs(context: Context): List<RecognizedSong> {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        val list = mutableListOf<RecognizedSong>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val plain = o.optString("plainLyrics", "")
                list.add(
                    RecognizedSong(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        stationName = o.optString("stationName"),
                        frequencyText = o.optString("frequencyText"),
                        recognizedAt = o.optString("recognizedAt"),
                        plainLyrics = plain,
                        syncedLyrics = parseLrcText("", plain),
                        sourceMethod = o.optString("sourceMethod", "선곡표/LRCLIB")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveSongToHistory(context: Context, song: RecognizedSong): List<RecognizedSong> {
        val current = loadSavedSongs(context).toMutableList()
        current.removeAll { it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true) }
        current.add(0, song)
        persistSongs(context, current.take(50))
        return current.take(50)
    }

    fun deleteSongFromHistory(context: Context, songId: String): List<RecognizedSong> {
        val current = loadSavedSongs(context).filterNot { it.id == songId }
        persistSongs(context, current)
        return current
    }

    fun clearAllHistory(context: Context): List<RecognizedSong> {
        persistSongs(context, emptyList())
        return emptyList()
    }

    private fun persistSongs(context: Context, songs: List<RecognizedSong>) {
        val arr = JSONArray()
        songs.forEach { s ->
            val obj = JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("artist", s.artist)
                put("album", s.album)
                put("stationName", s.stationName)
                put("frequencyText", s.frequencyText)
                put("recognizedAt", s.recognizedAt)
                put("plainLyrics", s.plainLyrics)
                put("sourceMethod", s.sourceMethod)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY_JSON, arr.toString())
            .apply()
    }

    private fun nowTimeString(): String {
        val sdf = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA)
        return sdf.format(Date())
    }
}