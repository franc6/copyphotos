package app.copyphotos.remote

import app.copyphotos.core.DestinationConfig
import app.copyphotos.core.DestinationError
import app.copyphotos.core.RemoteConnector
import app.copyphotos.core.RemoteEntry
import app.copyphotos.core.RemoteFileSystem
import app.copyphotos.core.SmbAuth
import app.copyphotos.data.PasswordStore
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Connects to SMB 2/3 servers with smbj, for `DestinationService`: NTLMv2 for username and
 * password, or guest and anonymous sessions. Connect and sign-in failures become
 * [DestinationError]s here, so no SMB type leaves `remote`. All calls block.
 *
 * @param passwords where the password is saved; read only while signing in.
 */
class SmbConnector(private val passwords: PasswordStore) : RemoteConnector {
    private fun config(encrypt: Boolean): SmbConfig = SmbConfig.builder()
        // Android has no JCE MD4; use smbj's BouncyCastle provider directly.
        .withSecurityProvider(BCSecurityProvider())
        .withTimeout(60, TimeUnit.SECONDS)
        .withSoTimeout(60, TimeUnit.SECONDS)
        .withEncryptData(encrypt)
        .build()

    private fun authentication(c: DestinationConfig, password: CharArray): AuthenticationContext = when (c.smbAuth) {
        SmbAuth.GUEST -> AuthenticationContext.guest()
        SmbAuth.ANONYMOUS -> AuthenticationContext.anonymous()
        SmbAuth.USER_PASSWORD -> AuthenticationContext(c.username.trim(), password, c.domain.trim().ifBlank { null })
    }

    private fun openSession(c: DestinationConfig): Triple<SMBClient, Connection, Session> {
        val client = SMBClient(config(c.encrypted))
        val password = passwords.read()
        val auth = authentication(c, password)
        try {
            val conn = c.port?.let { client.connect(c.host, it) } ?: client.connect(c.host)
            val session = conn.authenticate(auth)
            return Triple(client, conn, session)
        } catch (e: Exception) {
            client.close()
            throw destinationError(e, c.host, share = null)
        } finally {
            // Signing in is the only use of the password; wipe it (and smbj's copy, if it made one).
            auth.password?.fill('\u0000')
            password.fill('\u0000')
        }
    }

    override fun open(config: DestinationConfig): RemoteFileSystem {
        val c = config
        val (client, conn, session) = openSession(c)
        try {
            val share = session.connectShare(c.share) as? DiskShare
                ?: throw DestinationError.NotADiskShare(c.host, c.share)
            return SmbFileSystem(client, conn, session, share)
        } catch (e: Exception) {
            client.close()
            throw destinationError(e, c.host, c.share)
        }
    }

    /**
     * Enumerates disk shares through the srvsvc named pipe (NetrShareEnum), encrypted like
     * every other connection. (iOS can't: AMSMB2 always lists shares unencrypted.)
     */
    override fun listShares(config: DestinationConfig): List<RemoteEntry> {
        val c = config
        val (client, _, session) = openSession(c)
        client.use {
            val shares = try {
                ServerService(SMBTransportFactories.SRVSVC.getTransport(session)).shares1
            } catch (e: Exception) {
                throw destinationError(e, c.host, share = null)
            } catch (_: NoClassDefFoundError) {
                // dcerpc reports a malformed reply with java.rmi.UnmarshalException, which Android lacks.
                throw DestinationError.Other(c.host, 0, null)
            }
            return shares
                // STYPE_DISKTREE = 0: skip IPC$ and printers. Which disk shares to show is up to core.
                .filter { (it.type and 0x0FFFFFFF) == 0 }
                .map { RemoteEntry(it.netName, true, 0) }
        }
    }

    /**
     * A connect or sign-in failure as a [DestinationError], so no SMB type leaves this file.
     * @param e what smbj or the network threw.
     * @param host the server.
     * @param share the share being connected, or null while signing in or listing shares.
     * @return the matching [DestinationError].
     */
    private fun destinationError(e: Exception, host: String, share: String?): DestinationError {
        val causes = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
        causes.firstNotNullOfOrNull { it as? DestinationError }?.let { return it }
        causes.firstNotNullOfOrNull { it as? SMBApiException }?.let { return statusError(it, host, share) }
        return when {
            causes.any { it is UnknownHostException } -> DestinationError.InvalidHost
            causes.any { it is SocketTimeoutException } -> DestinationError.TimedOut(host)
            causes.any { it is NoRouteToHostException } -> DestinationError.Unreachable(host)
            causes.any { it is ConnectException } -> DestinationError.ConnectionRefused(host)
            else -> DestinationError.Other(host, 0, e.message)
        }
    }

    /**
     * The [DestinationError] an SMB status stands for.
     * @param api the SMB error the server returned.
     * @param host the server.
     * @param share the share being connected, or null while signing in or listing shares.
     * @return the matching [DestinationError].
     */
    private fun statusError(api: SMBApiException, host: String, share: String?): DestinationError = when (api.status) {
        NtStatus.STATUS_LOGON_FAILURE, NtStatus.STATUS_PASSWORD_EXPIRED, NtStatus.STATUS_ACCOUNT_DISABLED,
        NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED -> DestinationError.SignInRefused(host, share)
        NtStatus.STATUS_ACCESS_DENIED -> DestinationError.AccessDenied(host, share)
        NtStatus.STATUS_BAD_NETWORK_NAME ->
            if (share != null) DestinationError.ShareMissing(host, share) else DestinationError.Unreachable(host)
        else -> DestinationError.Other(host, api.statusCode.toInt(), api.message)
    }
}
