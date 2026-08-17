package io.tapstate.control.restapi;

import io.tapstate.control.core.TapstatePrincipal;
import io.tapstate.control.core.TokenService;
import io.tapstate.control.core.VerifiedToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import java.util.Objects;
import java.util.Optional;

/** Authenticates revocable machine tokens through the existing token service. */
final class MachineTokenAuthenticationProvider implements AuthenticationProvider {

    private final TokenService tokens;

    MachineTokenAuthenticationProvider(TokenService tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        TapstateCredentialAuthenticationToken credential = (TapstateCredentialAuthenticationToken) authentication;
        if (!TokenService.isMachineToken(credential.credential())) {
            return null;
        }
        Optional<VerifiedToken> verified = tokens.authenticate(credential.credential());
        if (verified.isEmpty()) {
            throw new BadCredentialsException("invalid bearer credential");
        }
        return new TapstateAuthentication(TapstatePrincipal.machineToken(verified.get()));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return TapstateCredentialAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
