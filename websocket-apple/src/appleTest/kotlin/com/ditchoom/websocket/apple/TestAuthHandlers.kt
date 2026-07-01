@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.ditchoom.websocket.apple

import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust

/**
 * Trust handler for tests against public TLS endpoints from a K/N test
 * binary spawned via `xcrun simctl spawn`. Default NSURLSession trust
 * evaluation in that context returns -1202 (untrusted) for any HTTPS host
 * — Safari trusts the same chain fine, but the spawn environment lacks
 * app-level trust eval entitlements (verified via plain
 * `dataTaskWithURL` returning the same -1202).
 *
 * For server-trust challenges, accepts whatever cert the server presents
 * and lets the system handle the connection. For non-trust challenges
 * (client cert, basic auth) returns null so the system handles them.
 *
 * Test-only — never use in production code.
 */
internal fun acceptServerPresentedTrust(): (NSURLAuthenticationChallenge) -> NSURLCredential? =
    { challenge ->
        if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
            val trust = challenge.protectionSpace.serverTrust
            if (trust != null) {
                NSURLCredential.credentialForTrust(trust)
            } else {
                null
            }
        } else {
            null
        }
    }
