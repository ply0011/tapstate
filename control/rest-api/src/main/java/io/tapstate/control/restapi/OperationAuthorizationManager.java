package io.tapstate.control.restapi;

import io.tapstate.control.core.Operation;
import io.tapstate.control.core.OperationRegistry;
import io.tapstate.control.core.TapstatePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Authorizes a mapped REST verb from its {@link Verb} id and the central operation registry.
 *
 * <p>No route-to-scope table lives here: MVC resolves the handler, the handler declares the operation id,
 * and the registry supplies the required scope. An unrecognised API route or handler is denied rather than
 * accidentally inheriting a broad rule.
 */
final class OperationAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    static final String OPERATION_ATTRIBUTE = OperationAuthorizationManager.class.getName() + ".operation";

    private final RequestMappingHandlerMapping handlers;
    private final OperationRegistry registry;

    OperationAuthorizationManager(RequestMappingHandlerMapping handlers, OperationRegistry registry) {
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        HttpServletRequest request = context.getRequest();
        Operation operation = operationFor(request);
        if (operation == null) {
            return new AuthorizationDecision(false);
        }
        request.setAttribute(OPERATION_ATTRIBUTE, operation);
        Authentication current = authentication.get();
        if (!(current != null && current.isAuthenticated() && current.getPrincipal() instanceof TapstatePrincipal principal)) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(principal.permits(operation.scope()));
    }

    private Operation operationFor(HttpServletRequest request) {
        try {
            HandlerExecutionChain chain = handlers.getHandler(request);
            if (chain == null || !(chain.getHandler() instanceof HandlerMethod handler)) {
                return null;
            }
            Verb verb = handler.getMethodAnnotation(Verb.class);
            return verb == null ? null : registry.find(verb.value()).orElse(null);
        } catch (Exception mappingFailure) {
            return null;
        }
    }
}
