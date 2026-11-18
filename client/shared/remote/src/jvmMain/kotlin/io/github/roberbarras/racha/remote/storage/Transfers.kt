package io.github.roberbarras.racha.remote.storage

import io.github.roberbarras.racha.application.sync.RemoteError
import io.github.roberbarras.racha.domain.remote.RemoteCourses
import io.github.roberbarras.racha.domain.remote.RemoteFile
import io.github.roberbarras.racha.remote.HttpOptions
import io.github.roberbarras.racha.remote.okHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/** HTTP for remote folders (H19): the app's proxy and User-Agent, long transfers allowed. */
internal class RemoteHttp(
    private val engineFactory: (HttpOptions) -> HttpClientEngine = ::okHttp,
    private val options: suspend () -> HttpOptions = { HttpOptions() },
) {
    suspend fun <T> use(block: suspend (HttpClient) -> T): T {
        val config = options()
        HttpClient(engineFactory(config)) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = 60_000 // between bytes; a whole video may take hours
            }
            install(UserAgent) { agent = config.userAgent }
        }.use { client ->
            return try {
                block(client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteError) {
                throw e
            } catch (e: IOException) {
                throw RemoteError.Unreachable(e)
            }
        }
    }

    companion object {
        fun check(response: HttpResponse, what: String) {
            if (response.status == HttpStatusCode.Unauthorized) throw RemoteError.Unauthorized()
            if (!response.status.isSuccess()) throw RemoteError.Rejected(response.status.value, null, "$what: HTTP ${response.status.value}")
        }
    }
}

/**
 * A download into `target` + [RemoteCourses.PART], continuing what is there (`Range`), renamed once complete and
 * given the remote modification time, so the next comparison sees it as up to date.
 */
internal suspend fun HttpClient.downloadTo(
    file: RemoteFile,
    target: String,
    progress: (Long) -> Unit,
    request: HttpRequestBuilder.() -> Unit,
) = withContext(Dispatchers.IO) {
    val final = Path.of(URI(target))
    val part = final.resolveSibling(final.fileName.toString() + RemoteCourses.PART)
    Files.createDirectories(final.parent)
    var offset = if (Files.exists(part)) Files.size(part) else 0L
    if (offset > file.size) { Files.delete(part); offset = 0 }
    if (offset < file.size || file.size == 0L) {
        prepareRequest {
            method = HttpMethod.Get
            request()
            if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
        }.execute { response ->
            RemoteHttp.check(response, "download")
            // A server that ignores the range sends everything again: start over.
            if (offset > 0 && response.status != HttpStatusCode.PartialContent) offset = 0
            FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { out ->
                if (offset == 0L) out.truncate(0)
                out.position(offset)
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(256 * 1024)
                var written = offset
                var reported = 0L
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) { if (channel.isClosedForRead) break else continue }
                    out.write(ByteBuffer.wrap(buffer, 0, read))
                    written += read
                    if (written - reported >= 1 shl 20) { progress(written); reported = written }
                }
                progress(written)
            }
        }
    }
    val size = Files.size(part)
    if (file.size > 0 && size != file.size) throw IOException("incomplete: $size of ${file.size} bytes")
    Files.move(part, final, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    Files.setLastModifiedTime(final, FileTime.fromMillis(file.modifiedAt.toEpochMilliseconds()))
    Unit
}
