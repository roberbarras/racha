package io.github.roberbarras.racha.android.resource

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import io.github.roberbarras.racha.domain.library.RelativePath
import io.github.roberbarras.racha.domain.resource.ExternalLinks
import java.io.File
import java.io.FileInputStream

/** Origin of course pages: an https address the WebView never resolves on the network (WebViewAssetLoader). */
internal const val COURSE_HOST = WebViewAssetLoader.DEFAULT_DOMAIN
internal const val COURSE_PREFIX = "/course/"

/**
 * Serves files of one course folder under [COURSE_PREFIX] (ADR-0003): only regular files whose canonical path is
 * inside the folder (no `..`, no symlinks out); anything else is a 404. Pages keep their relative CSS/JS/images.
 */
internal class CourseFolderHandler(folder: String) : WebViewAssetLoader.PathHandler {
    private val root: File = File(folder).canonicalFile

    override fun handle(path: String): WebResourceResponse {
        val file = runCatching { File(root, path).canonicalFile }.getOrNull()
        if (file == null || !file.isFile || !file.path.startsWith(root.path + File.separator)) return notFound()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        return WebResourceResponse(mime, if (mime.startsWith("text/")) "utf-8" else null, FileInputStream(file)).apply {
            responseHeaders = mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
}

internal fun coursePageUrl(page: RelativePath): String =
    Uri.Builder().scheme("https").authority(COURSE_HOST).encodedPath(COURSE_PREFIX)
        .appendEncodedPath(page.value.split('/').joinToString("/") { Uri.encode(it) }).build().toString()

/**
 * A WebView locked to one course: no file:// or content:// access, JavaScript on for quizzes with no bridge to the
 * app, and every navigation that leaves the course goes to [onExternalLink] instead.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun courseWebView(context: Context, folder: String, onExternalLink: (String) -> Unit): WebView {
    val loader = WebViewAssetLoader.Builder().setDomain(COURSE_HOST).addPathHandler(COURSE_PREFIX, CourseFolderHandler(folder)).build()
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true // quizzes keep answers in localStorage
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.host == COURSE_HOST) return loader.shouldInterceptRequest(uri)
                // Sub-resources from the internet (fonts, CDNs) are allowed; top-level navigation is handled below.
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == COURSE_HOST) return false
                request.url.toString().takeIf(ExternalLinks::isSafe)?.let(onExternalLink)
                return true
            }
        }
    }
}

@Composable
internal fun CourseHtmlView(folder: String, page: RelativePath, onExternalLink: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember(folder, page) { courseWebView(context, folder, onExternalLink).also { it.loadUrl(coursePageUrl(page)) } }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(view) {
        onDispose {
            view.stopLoading()
            view.destroy()
        }
    }
}
