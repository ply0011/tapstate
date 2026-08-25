package io.tapstate.control.restapi;

import io.tapstate.control.core.TapstatePrincipal;
import io.tapstate.control.core.TokenService;
import io.tapstate.control.core.TokenSigner;
import io.tapstate.control.core.VerifiedToken;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import java.util.Objects;
import java.util.Optional;

/** Authenticates signed human session JWTs through the existing framework-free token signer. */
final class HumanJwtAuthenticationProvider implements AuthenticationProvider {

    private final TokenSigner signer;

    HumanJwtAuthenticationProvider(TokenSigner signer) {
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        TapstateCredentialAuthenticationToken credential = (TapstateCredentialAuthenticationToken) authentication;
        if (TokenService.isMachineToken(credential.credential())) {
            return null;
        }
        Optional<VerifiedToken> verified = signer.verify(credential.credential());
        if (verified.isEmpty()) {
            throw new BadCredentialsException("invalid bearer credential");
        }
        return new TapstateAuthentication(TapstatePrincipal.humanJwt(verified.get()));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return TapstateCredentialAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
