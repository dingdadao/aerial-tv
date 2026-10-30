package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The wallpapers Pap.er keeps: photographs picked at random from `api.paperapp.net`, where the
 * phone app of that name gets its pictures (the owner, 2026-10-30). One call does it — a GraphQL
 * query at `POST /graphql` answers with photographs, each already carrying the address of its full
 * picture, so there is no separate listing call and no token: ask, then download.
 *
 * A photograph arrives at several thousand pixels across and a few megabytes; it is cut down to
 * [MAX_W] wide and kept as a JPEG in `files/paper/`, with [KEEP] of them at most (about 10 MB),
 * the newest last in. The picks are also what a shuffle falls back on when the network is not
 * there, so a TV left without one still has something to change to.
 *
 * Everything here runs off the main thread. Nothing is written outside `files/paper/`.
 */
object Papers {
    private const val TAG = "AerialPapers"
    private const val HOST = "api.paperapp.net"
    /**
     * Asked for [HOST]'s addresses instead of the router's resolver, which hands out an edge this
     * zone is not served from (see [post]). AliDNS and DNSPod, both reachable from China without a
     * proxy; the system's answer is used if neither replies.
     */
    private val RESOLVERS = listOf("223.5.5.5", "119.29.29.29")
    /**
     * Cloudflare in front of the API answers 403 ("error code: 1010") to some agents — Python's
     * urllib and libwww-perl among them — and lets a client's through; Android's own Dalvik agent
     * is untested, so one of the client ones the API documents as fine is sent instead.
     */
    private const val AGENT = "okhttp/4.12.0"
    /** How many photographs are kept on disk: what a shuffle falls back on, and what the picker lists. */
    const val KEEP = 12
    /** Wide enough for a 1080p screen with room for the crop; a 4K interface upscales a little. */
    private const val MAX_W = 2560
    /** How many the API is asked for at once, to pick a wide one out of. */
    private const val ASK = 15
    /** The API's own limit per call. */
    private const val MAX_ASK = 50

    /**
     * One photograph: [title] names it in the picker (who took it), [caption] is what the
     * screensaver puts under its picture; [added] sorts them, newest first in the picker.
     */
    data class Shot(val id: String, val title: String, val caption: String, val added: Long)

    /** Where the pictures are kept; null until [init]. */
    private var dir: File? = null

    /** The platform's TLS, for [Pinned]; built once, on first use. */
    private val tls: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, null, null) }.socketFactory
    }

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.filesDir, "paper").apply { mkdirs() }
    }

    fun file(id: String): File? = dir?.let { File(it, "$id.jpg") }

    /** The photographs on disk, newest first. Photographs whose file has gone are left out. */
    fun all(): List<Shot> {
        val d = dir ?: return emptyList()
        return runCatching {
            val arr = JSONArray(File(d, "index.json").readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Shot(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    caption = o.optString("caption"),
                    added = o.optLong("added"),
                )
            }.filter { File(d, it.id + ".jpg").exists() }.sortedByDescending { it.added }
        }.getOrDefault(emptyList())
    }

    /** The photographs to keep the API from handing back: the ones already here. */
    fun taken(): Set<String> = all().map { it.id }.toSet()

    /**
     * Asks for a handful and keeps one of them: a picture wider than it is tall, for a 16:9 screen,
     * cut down and written to disk. Null where the call or the download did not come through — no
     * network, or the API answering with something other than photographs.
     */
    suspend fun fetch(taken: Set<String> = taken()): Shot? = withContext(Dispatchers.IO) {
        val photos = runCatching { query(taken) }
            .onFailure { Log.w(TAG, "no photographs", it) }
            .getOrDefault(emptyList())
        // A TV screen: the wide ones, and nothing nearly square or upright, unless that is all there is.
        val wide = photos.filter { it.width >= it.height * 1.4f }.ifEmpty { photos.filter { it.width > it.height } }
        val pick = (wide.ifEmpty { photos }).randomOrNull() ?: return@withContext null
        val bytes = runCatching { download(pick.full) }
            .onFailure { Log.w(TAG, "could not download ${pick.id}", it) }
            .getOrNull() ?: return@withContext null
        val shot = runCatching { keep(pick, bytes) }
            .onFailure { Log.w(TAG, "could not keep ${pick.id}", it) }
            .getOrNull() ?: return@withContext null
        Log.i(TAG, "kept ${shot.id}, ${bytes.size / 1024} KB from the API")
        shot
    }

    /** Drops everything, for a TV given back as new. */
    fun clear() {
        val d = dir ?: return
        d.listFiles()?.forEach { it.delete() }
    }

    // ---- the call ----

    private class Found(val id: String, val width: Int, val height: Int, val full: String, val title: String, val caption: String)

    /**
     * `randomPhotos` answers with an array of photographs, not the wrapped page `photos` does.
     * [known] goes in `latestIds`, which the API leaves out of its picks, so a shuffle hands back
     * a picture this TV does not already have.
     */
    private fun query(known: Set<String>): List<Found> {
        val query = "query(\$count:Int,\$latestIds:[ID]){randomPhotos(count:\$count,latestIds:\$latestIds)" +
            "{id width height creator heading urls{full}}}"
        val variables = JSONObject().put("count", ASK.coerceAtMost(MAX_ASK)).put("latestIds", JSONArray(known.toList()))
        val answer = post(JSONObject().put("query", query).put("variables", variables).toString())
        val photos = JSONObject(answer).optJSONObject("data")?.optJSONArray("randomPhotos") ?: return emptyList()
        return (0 until photos.length()).mapNotNull { i ->
            val o = photos.optJSONObject(i) ?: return@mapNotNull null
            val full = o.optJSONObject("urls")?.optString("full").orEmpty()
            val id = o.optString("id")
            if (id.isEmpty() || full.isEmpty()) return@mapNotNull null
            // "heading" is what the phone app prints under a picture ("@Chris Curry / Unsplash");
            // the creator alone is what fits under the screensaver's clock.
            val creator = o.optString("creator").trim().removePrefix("@")
            val heading = o.optString("heading").trim().substringBefore(" / ").removePrefix("@").ifBlank { creator }
            Found(
                id = id,
                width = o.optInt("width"),
                height = o.optInt("height"),
                full = full,
                title = heading.ifBlank { "Pap.er" },
                caption = creator,
            )
        }
    }

    /** An id is a UUID, which is a plain file name; anything else is refused rather than written. */
    private fun safe(id: String): Boolean = id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * The API answers 403 "error code: 1034" whenever the name is dialled on one particular
     * address, and a router's DNS can hand that address out beside the right ones (2026-10-30: the
     * owner's `10.0.0.30` answered `198.41.213.71` — not an edge this zone is served from, so
     * Cloudflare refuses the whole request — together with `104.27.10.74`, which answers 200;
     * 223.5.5.5 and 119.29.29.29 hand out only the right ones). A 403 is a well-formed answer, so
     * nothing below moves on to the next address by itself.
     *
     * Android offers no way to change a TV's resolver, so this asks [RESOLVERS] directly and dials
     * what they say, one address after another, until one answers: the bad address costs one
     * refused request instead of the whole shuffle. The name still goes in the handshake, so the
     * certificate is checked against it as usual.
     */
    private fun post(body: String): String {
        val addresses = addresses()
        if (addresses.isEmpty()) throw IllegalStateException("no address for $HOST")
        var last: Throwable? = null
        for (address in addresses) {
            val answer = runCatching { postOnce(body, address) }
            answer.getOrNull()?.let { return it }
            last = answer.exceptionOrNull()
            Log.w(TAG, "no answer from ${address.hostAddress}: ${last?.message}")
        }
        throw last ?: IllegalStateException("no answer from the API")
    }

    /**
     * The addresses [HOST] has, asked of [RESOLVERS] first — a TV's own resolver is the router's and
     * cannot be changed (see [post]). A network that blocks port 53 outwards gets the system's
     * answer, which is no worse than before.
     */
    private fun addresses(host: String = HOST): List<InetAddress> {
        for (resolver in RESOLVERS) {
            val found = runCatching { ask(resolver, host) }.getOrNull().orEmpty()
            if (found.isNotEmpty()) {
                Log.i(TAG, "$host via $resolver: ${found.joinToString { it.hostAddress ?: "?" }}")
                return found
            }
        }
        val system = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull().orEmpty()
        Log.w(TAG, "no answer from $RESOLVERS for $host, system says ${system.joinToString { it.hostAddress ?: "?" }}")
        return system
    }

    /** One A query over UDP: one name, one record type, so the packet is written by hand. */
    private fun ask(resolver: String, name: String): List<InetAddress> {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val bytes = java.io.ByteArrayOutputStream().apply {
            write(id shr 8); write(id and 0xFF)          // id
            write(0x01); write(0x00)                     // standard query, recursion wanted
            write(0x00); write(0x01)                     // one question
            repeat(6) { write(0) }                       // no answers, authority or extra
            for (label in name.split('.')) {
                write(label.length); write(label.toByteArray(Charsets.US_ASCII))
            }
            write(0)                                     // end of the name
            write(0x00); write(0x01)                     // type A
            write(0x00); write(0x01)                     // class IN
        }.toByteArray()
        DatagramSocket().use { socket ->
            socket.soTimeout = 3_000
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(resolver), 53))
            val buf = ByteArray(1_500)
            val answer = DatagramPacket(buf, buf.size)
            socket.receive(answer)
            return parseA(buf, answer.length, id)
        }
    }

    /** The A records in a DNS answer; anything malformed ends the walk rather than crashing it. */
    private fun parseA(buf: ByteArray, len: Int, id: Int): List<InetAddress> {
        fun u8(i: Int) = buf[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (len < 12 || u16(0) != id) return emptyList()
        val answers = u16(6)
        if (answers == 0) return emptyList()
        var i = 12
        while (i < len) {                                // past the question's name
            val b = u8(i)
            if (b and 0xC0 == 0xC0) { i += 2; break }
            i += 1
            if (b == 0) break
            i += b
        }
        i += 4                                           // its type and class
        val out = ArrayList<InetAddress>()
        repeat(answers) {
            while (i < len) {                            // each answer's name, usually a pointer
                val b = u8(i)
                if (b and 0xC0 == 0xC0) { i += 2; break }
                i += 1
                if (b == 0) break
                i += b
            }
            if (i + 10 > len) return out
            val type = u16(i)
            val size = u16(i + 8)
            i += 10
            if (type == 1 && size == 4 && i + 4 <= len) out += InetAddress.getByAddress(buf.copyOfRange(i, i + 4))
            i += size
        }
        return out
    }

    private fun postOnce(body: String, address: InetAddress): String =
        call(address, HOST, "/graphql", body, 12_000).decodeToString()

    /**
     * One request written out on a [Pinned] socket. The platform's HTTP stack looks the name up
     * again itself before it connects, which is the whole thing being avoided here, so the request
     * goes out by hand: one of them, `Connection: close`, read until the socket ends.
     */
    private fun call(address: InetAddress, host: String, path: String, body: String?, timeout: Int): ByteArray {
        Pinned(address, tls, host, timeout).createSocket().use { socket ->
            val head = StringBuilder()
                .append(if (body == null) "GET" else "POST").append(' ').append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("User-Agent: ").append(AGENT).append("\r\n")
                .append("Accept: */*\r\n")
                .append("Connection: close\r\n")
            body?.let {
                head.append("Content-Type: application/json\r\n")
                head.append("Content-Length: ").append(it.toByteArray().size).append("\r\n")
            }
            val out = socket.getOutputStream()
            out.write(head.append("\r\n").toString().toByteArray())
            body?.let { out.write(it.toByteArray()) }
            out.flush()
            val raw = socket.getInputStream().readBytes()
            val end = raw.headerEnd()
            if (end < 0) error("no answer from ${address.hostAddress} ($host)")
            val top = String(raw, 0, end, Charsets.ISO_8859_1)
            val code = top.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0
            if (code != 200) error("HTTP $code from ${address.hostAddress} ($host)")
            val rest = raw.copyOfRange(end + 4, raw.size)
            return if (top.contains("chunked", ignoreCase = true)) rest.unchunked() else rest
        }
    }

    private fun ByteArray.headerEnd(): Int {
        for (i in 0..size - 4) {
            if (this[i] == 13.toByte() && this[i + 1] == 10.toByte() &&
                this[i + 2] == 13.toByte() && this[i + 3] == 10.toByte()
            ) return i
        }
        return -1
    }

    /** A chunked body cut back to what it carries; a malformed one ends the walk, not the app. */
    private fun ByteArray.unchunked(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < size) {
            var j = i
            while (j + 1 < size && !(this[j] == 13.toByte() && this[j + 1] == 10.toByte())) j++
            val size = String(this, i, j - i, Charsets.ISO_8859_1).substringBefore(';').trim().toIntOrNull(16)
                ?: return out.toByteArray()
            i = j + 2
            if (size == 0) return out.toByteArray()
            out.write(this, i, minOf(size, this.size - i))
            i += size + 2
        }
        return out.toByteArray()
    }

    /**
     * Dials [address] while carrying [host] in the handshake — the certificate is the one for the
     * name and is checked against it (endpoint identification on), so pinning the address does not
     * weaken anything; it only decides which edge answers.
     */
    private class Pinned(
        private val address: InetAddress,
        private val tls: SSLSocketFactory,
        private val host: String,
        private val timeout: Int,
    ) : SSLSocketFactory() {
        private fun wrap(port: Int): Socket {
            val raw = Socket()
            raw.connect(InetSocketAddress(address, port), 8_000)
            raw.soTimeout = timeout
            val s = tls.createSocket(raw, host, port, true) as SSLSocket
            s.sslParameters = s.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                serverNames = listOf(SNIHostName(host))
            }
            return s
        }

        override fun createSocket(): Socket = wrap(443)
        override fun createSocket(host: String?, port: Int): Socket = wrap(port)
        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket = wrap(port)
        override fun createSocket(host: InetAddress?, port: Int): Socket = wrap(port)
        override fun createSocket(host: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket = wrap(port)
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            tls.createSocket(s, this.host, port, autoClose)
        override fun getDefaultCipherSuites(): Array<String> = tls.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = tls.supportedCipherSuites
    }

    /** The picture, off the first of the host's addresses that answers; pictures are on another
     *  host than the API (`img.paperapp.net`), so its addresses are asked of the same resolvers. */
    private fun download(url: String): ByteArray {
        val addresses = addresses(URL(url).host)
        if (addresses.isEmpty()) throw IllegalStateException("no address for ${URL(url).host}")
        var last: Throwable? = null
        for (address in addresses) {
            val answer = runCatching { downloadOnce(url, address) }
            answer.getOrNull()?.let { return it }
            last = answer.exceptionOrNull()
            Log.w(TAG, "no picture from ${address.hostAddress}: ${last?.message}")
        }
        throw last ?: IllegalStateException("no picture at $url")
    }

    private fun downloadOnce(url: String, address: InetAddress): ByteArray {
        val u = URL(url)
        return call(address, u.host, u.file.ifEmpty { "/" }, null, 30_000)
    }

    /** Cuts the picture down to [MAX_W] at most and writes it, then keeps the newest [KEEP]. */
    private fun keep(found: Found, bytes: ByteArray): Shot? {
        if (!safe(found.id)) return null
        val d = dir ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_W) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scaled = if (decoded.width > MAX_W) {
            Bitmap.createScaledBitmap(decoded, MAX_W, (decoded.height.toLong() * MAX_W / decoded.width).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        val file = File(d, found.id + ".jpg")
        val written = runCatching {
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }.getOrDefault(false)
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        if (!written) {
            file.delete()
            return null
        }
        val shot = Shot(found.id, found.title, found.caption, System.currentTimeMillis())
        writeIndex(listOf(shot) + all().filter { it.id != shot.id })
        prune()
        return shot
    }

    private fun writeIndex(shots: List<Shot>) {
        val d = dir ?: return
        val arr = JSONArray()
        shots.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("caption", it.caption)
                    .put("added", it.added),
            )
        }
        File(d, "index.json").writeText(arr.toString())
    }

    /** Keeps the newest [KEEP]; the index goes first, so a file cut short of it is simply not listed. */
    private fun prune() {
        val d = dir ?: return
        val shots = all()
        val keep = shots.take(KEEP)
        if (keep.size != shots.size) writeIndex(keep)
        val ids = keep.map { it.id + ".jpg" }.toSet()
        d.listFiles { f -> f.extension == "jpg" && f.name !in ids }?.forEach { it.delete() }
    }
}
