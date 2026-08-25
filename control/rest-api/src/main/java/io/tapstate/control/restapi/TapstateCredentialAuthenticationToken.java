package io.tapstate.control.restapi;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;
import java.util.Objects;

/** An unverified bearer credential presented to the Tapstate authentication providers. */
final class TapstateCredentialAuthenticationToken extends AbstractAuthenticationToken {

    private final String credential;

    TapstateCredentialAuthenticationToken(String credential) {
        super(List.of());
        this.credential = Objects.requireNonNull(credential, "credential");
        setAuthenticated(false);
    }

    String credential() {
        return credential;
    }

    @Override
    public Object getCredentials() {
        return credential;
    }

    @Override
    public Object getPrincipal() {
        return null;
    }
}
