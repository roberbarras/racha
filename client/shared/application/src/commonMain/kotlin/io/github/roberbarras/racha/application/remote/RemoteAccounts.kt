package io.github.roberbarras.racha.application.remote

import io.github.roberbarras.racha.application.shared.SecretStore

/** A Nextcloud sign-in waiting for the user in the browser. */
data class NextcloudPending(val loginUrl: String, val endpoint: String, val token: String)

/** What a finished Nextcloud sign-in gives: where the files are and an app password for them. */
data class WebDavAccount(val server: String, val user: String, val password: String, val davBase: String)

interface NextcloudSignIn {
    suspend fun start(server: String): NextcloudPending

    /** Null while the user has not finished in the browser. */
    suspend fun poll(pending: NextcloudPending): WebDavAccount?

    /** The WebDAV folder of a Nextcloud user (manual sign-in with an app password). */
    fun davBase(server: String, user: String): String
}

/** Google's consent in the browser; returns the refresh token. Needs the user's OAuth client ([RemoteAccounts]). */
fun interface GoogleSignIn {
    suspend fun signIn(openBrowser: (String) -> Unit): String
}

/**
 * Signing in to remote folders (H19). Google Drive needs an OAuth client of the user's own (Google does not let an
 * open-source app ship one usable by everyone): its id and secret are kept in the keyring, like every credential.
 */
class RemoteAccounts(
    private val nextcloud: NextcloudSignIn,
    private val google: GoogleSignIn,
    private val googleClient: SecretStore,
) {
    suspend fun googleReady(): Boolean = googleClient.read()?.lines()?.let { it.size >= 2 && it[0].isNotBlank() && it[1].isNotBlank() } == true

    suspend fun setGoogleClient(id: String, secret: String) = googleClient.write("${id.trim()}\n${secret.trim()}")

    suspend fun forgetGoogleClient() = googleClient.clear()

    suspend fun startNextcloud(server: String): NextcloudPending = nextcloud.start(server)

    suspend fun pollNextcloud(pending: NextcloudPending): WebDavAccount? = nextcloud.poll(pending)

    /** Signed in by hand: a Nextcloud server and app password, or any WebDAV URL with its user and password. */
    fun manual(serverOrUrl: String, user: String, password: String, nextcloudServer: Boolean): WebDavAccount {
        val url = serverOrUrl.trim().let { if ("://" in it) it else "https://$it" }
        val base = if (nextcloudServer) nextcloud.davBase(url, user.trim()) else url.trimEnd('/') + "/"
        return WebDavAccount(url, user.trim(), password, base)
    }

    suspend fun signInGoogle(openBrowser: (String) -> Unit): RemoteCredentials.Drive = RemoteCredentials.Drive(google.signIn(openBrowser))
}
