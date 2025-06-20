package dev.shibasis.reaktor.auth.framework

import dev.shibasis.reaktor.core.network.StatusCode
import org.springframework.http.HttpStatus

fun StatusCode.toHttpStatus(): HttpStatus =
    when (this) {
        StatusCode.CONTINUE -> HttpStatus.CONTINUE
        StatusCode.SWITCHING_PROTOCOLS -> HttpStatus.SWITCHING_PROTOCOLS
        StatusCode.PROCESSING -> HttpStatus.PROCESSING
        StatusCode.OK -> HttpStatus.OK
        StatusCode.CREATED -> HttpStatus.CREATED
        StatusCode.ACCEPTED -> HttpStatus.ACCEPTED
        StatusCode.NON_AUTHORITATIVE_INFORMATION -> HttpStatus.NON_AUTHORITATIVE_INFORMATION
        StatusCode.NO_CONTENT -> HttpStatus.NO_CONTENT
        StatusCode.RESET_CONTENT -> HttpStatus.RESET_CONTENT
        StatusCode.PARTIAL_CONTENT -> HttpStatus.PARTIAL_CONTENT
        StatusCode.MULTI_STATUS -> HttpStatus.MULTI_STATUS
        StatusCode.ALREADY_REPORTED -> HttpStatus.ALREADY_REPORTED
        StatusCode.IM_USED -> HttpStatus.IM_USED
        StatusCode.MULTIPLE_CHOICES -> HttpStatus.MULTIPLE_CHOICES
        StatusCode.MOVED_PERMANENTLY -> HttpStatus.MOVED_PERMANENTLY
        StatusCode.FOUND -> HttpStatus.FOUND
        StatusCode.SEE_OTHER -> HttpStatus.SEE_OTHER
        StatusCode.NOT_MODIFIED -> HttpStatus.NOT_MODIFIED
        StatusCode.USE_PROXY -> HttpStatus.USE_PROXY
        StatusCode.TEMPORARY_REDIRECT -> HttpStatus.TEMPORARY_REDIRECT
        StatusCode.PERMANENT_REDIRECT -> HttpStatus.PERMANENT_REDIRECT
        StatusCode.BAD_REQUEST -> HttpStatus.BAD_REQUEST
        StatusCode.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED
        StatusCode.PAYMENT_REQUIRED -> HttpStatus.PAYMENT_REQUIRED
        StatusCode.FORBIDDEN -> HttpStatus.FORBIDDEN
        StatusCode.NOT_FOUND -> HttpStatus.NOT_FOUND
        StatusCode.METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED
        StatusCode.NOT_ACCEPTABLE -> HttpStatus.NOT_ACCEPTABLE
        StatusCode.PROXY_AUTHENTICATION_REQUIRED -> HttpStatus.PROXY_AUTHENTICATION_REQUIRED
        StatusCode.REQUEST_TIMEOUT -> HttpStatus.REQUEST_TIMEOUT
        StatusCode.CONFLICT -> HttpStatus.CONFLICT
        StatusCode.GONE -> HttpStatus.GONE
        StatusCode.LENGTH_REQUIRED -> HttpStatus.LENGTH_REQUIRED
        StatusCode.PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED
        StatusCode.PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE
        StatusCode.URI_TOO_LONG -> HttpStatus.URI_TOO_LONG
        StatusCode.UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
        StatusCode.RANGE_NOT_SATISFIABLE -> HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE
        StatusCode.EXPECTATION_FAILED -> HttpStatus.EXPECTATION_FAILED
        StatusCode.IM_A_TEAPOT -> HttpStatus.I_AM_A_TEAPOT
        StatusCode.MISDIRECTED_REQUEST -> HttpStatus.DESTINATION_LOCKED
        StatusCode.UNPROCESSABLE_ENTITY -> HttpStatus.UNPROCESSABLE_ENTITY
        StatusCode.LOCKED -> HttpStatus.LOCKED
        StatusCode.FAILED_DEPENDENCY -> HttpStatus.FAILED_DEPENDENCY
        StatusCode.TOO_EARLY -> HttpStatus.TOO_EARLY
        StatusCode.UPGRADE_REQUIRED -> HttpStatus.UPGRADE_REQUIRED
        StatusCode.PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED
        StatusCode.TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS
        StatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE -> HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE
        StatusCode.UNAVAILABLE_FOR_LEGAL_REASONS -> HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS
        StatusCode.INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
        StatusCode.NOT_IMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED
        StatusCode.BAD_GATEWAY -> HttpStatus.BAD_GATEWAY
        StatusCode.SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
        StatusCode.GATEWAY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
        StatusCode.HTTP_VERSION_NOT_SUPPORTED -> HttpStatus.HTTP_VERSION_NOT_SUPPORTED
        StatusCode.VARIANT_ALSO_NEGOTIATES -> HttpStatus.VARIANT_ALSO_NEGOTIATES
        StatusCode.INSUFFICIENT_STORAGE -> HttpStatus.INSUFFICIENT_STORAGE
        StatusCode.LOOP_DETECTED -> HttpStatus.LOOP_DETECTED
        StatusCode.NOT_EXTENDED -> HttpStatus.NOT_EXTENDED
        StatusCode.NETWORK_AUTHENTICATION_REQUIRED -> HttpStatus.NETWORK_AUTHENTICATION_REQUIRED
    }
