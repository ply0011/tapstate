package io.tapstate.control.restapi;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.Enumeration;

/**
 * Converts exactly one syntactically valid {@code Authorization: Bearer <credential>} header to an
 * authentication request. Missing credentials remain absent so request authorization can select the normal
 * unauthenticated response; malformed or ambiguous headers become an invalid credential and receive the same
 * refusal without exposing parser details.
 */
final class StrictBearerAuthenticationConverter implements AuthenticationConverter {

    private static final String SCHEME = "Bearer";

    @Override
    public Authentication convert(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (!headers.hasMoreElements()) {
            return null;
        }
        String header = headers.nextElement();
        if (headers.hasMoreElements() || !isStrictBearer(header)) {
            return new TapstateCredentialAuthenticationToken("");
        }
        return new TapstateCredentialAuthenticationToken(header.substring(SCHEME.length() + 1));
    }

    private static boolean isStrictBearer(String header) {
        if (header == null || header.length() <= SCHEME.length() + 1
                || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())
                || header.charAt(SCHEME.length()) != ' ') {
            return false;
        }
        String credential = header.substring(SCHEME.length() + 1);
        return credential.indexOf(',') < 0 && credential.codePoints().noneMatch(Character::isWhitespace);
    }
}
