package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.core.network.StatusCode

/**
 * A failure a handler means to return, with the status it means to return it as.
 *
 * Without this every thrown exception reaches the transport identically and comes back as a 500,
 * which is wrong in both directions: a caller who sent a bad request is told the server broke, and
 * a caller who was refused cannot tell that from an outage. Rejected credentials in particular need
 * to be a 401 — a client that retries a 500 forever will happily do so against a revoked token.
 *
 * Anything *not* thrown as an [HttpFailure] is a bug rather than an answer, and a transport should
 * treat it as a 500 and say nothing further about it: an unplanned exception's message is written
 * for a developer reading a log, not for whoever is on the other end of the request.
 */
open class HttpFailure(
    val statusCode: StatusCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * The credential was absent, malformed, or no longer valid.
 *
 * Open, like the rest of these, so a service can subclass it and keep its own vocabulary — the
 * status is the part the transport needs, and the name is the part the service's code reads best.
 */
open class Unauthorized(message: String = "Unauthorized") :
    HttpFailure(StatusCode.UNAUTHORIZED, message)

/** The caller is known and is not allowed to do this. */
open class Forbidden(message: String = "Forbidden") : HttpFailure(StatusCode.FORBIDDEN, message)

/** The request was understood and is wrong. */
open class BadRequest(message: String = "Bad request") : HttpFailure(StatusCode.BAD_REQUEST, message)

/** No such thing, or none this caller may see. */
open class NotFound(message: String = "Not found") : HttpFailure(StatusCode.NOT_FOUND, message)
