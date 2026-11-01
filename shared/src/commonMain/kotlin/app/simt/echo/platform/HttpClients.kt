package app.simt.echo.platform

import io.ktor.client.HttpClient

/**
 * Platform HTTP engine for the cloud API client (plan W3-02): OkHttp on
 * Android, Darwin on iOS. Timeouts mirror the LAN transport (4 s connect,
 * 20 s request); uploads poll for minutes, so callers set no tighter
 * per-call deadline than the transport default.
 */
expect fun createHttpClient(): HttpClient
