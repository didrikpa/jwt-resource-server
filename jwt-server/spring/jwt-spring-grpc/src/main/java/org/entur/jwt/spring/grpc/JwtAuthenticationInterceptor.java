package org.entur.jwt.spring.grpc;

import io.grpc.*;
import io.grpc.ServerCall.Listener;
import org.entur.jwt.jwk.JwksClientException;
import org.entur.jwt.jwk.JwksException;
import org.entur.jwt.spring.filter.JwtAuthenticationToken;
import org.entur.jwt.spring.filter.JwtAuthorityMapper;
import org.entur.jwt.spring.filter.JwtDetailsMapper;
import org.entur.jwt.spring.filter.JwtPrincipalMapper;
import org.entur.jwt.spring.filter.log.JwtMappedDiagnosticContextMapper;
import org.entur.jwt.verifier.JwtClaimExtractor;
import org.entur.jwt.verifier.JwtClientException;
import org.entur.jwt.verifier.JwtException;
import org.entur.jwt.verifier.JwtVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class JwtAuthenticationInterceptor<T> implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationInterceptor.class);

    private String key = UUID.randomUUID().toString();

    public static final String AUTHORIZATION = "Authorization";
    public static final String BEARER = "Bearer ";

    private final JwtVerifier<T> verifier;
    private final JwtAuthorityMapper<T> authorityMapper;
    private final JwtMappedDiagnosticContextMapper<T> mdcMapper;
    private final JwtClaimExtractor<T> extractor;
    private final GrpcServiceMethodFilter anonymousMethodFilter;
    private final JwtDetailsMapper detailsMapper;
    private final JwtPrincipalMapper principalMapper;

    public JwtAuthenticationInterceptor(JwtVerifier<T> verifier, GrpcServiceMethodFilter anonymousMethodFilter, JwtAuthorityMapper<T> authorityMapper, JwtMappedDiagnosticContextMapper<T> mdcMapper, JwtClaimExtractor<T> extractor, JwtPrincipalMapper principalMapper, JwtDetailsMapper detailsMapper) {
        this.verifier = verifier;
        this.anonymousMethodFilter = anonymousMethodFilter;
        this.authorityMapper = authorityMapper;
        this.mdcMapper = mdcMapper;
        this.extractor = extractor;
        this.principalMapper = principalMapper;
        this.detailsMapper = detailsMapper;
    }
    
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String header = headers.get(Metadata.Key.of(AUTHORIZATION, Metadata.ASCII_STRING_MARSHALLER));

        if (header != null) {
            if(!header.startsWith(BEARER)) {
                // assume garbage from the internet
                log.debug("Invalid authorization header type");
                call.close(Status.UNAUTHENTICATED.withDescription("Invalid authorization header type"), new Metadata());
                return new Listener<ReqT>() {
                };
            }
            return interceptBearerTokenCall(call, headers, next, header);
        } else if (anonymousMethodFilter != null && anonymousMethodFilter.matches(call)) {
            AnonymousAuthenticationToken anonymousAuthenticationToken = new AnonymousAuthenticationToken(key, "anonymousUser",
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
            Context context = Context.current().withValue(GrpcAuthorization.SECURITY_CONTEXT_AUTHENTICATION, anonymousAuthenticationToken);
            return Contexts.interceptCall(context, call, headers, next); // sets the new context, then clears it again before returning
        } else {
            log.debug("Authentication is required, however there was no bearer token");
            call.close(Status.UNAUTHENTICATED.withDescription("Authorization header is missing"), new Metadata());
            return new Listener<ReqT>() {};            
        }        
    }

	protected <ReqT, RespT> ServerCall.Listener<ReqT> interceptBearerTokenCall(ServerCall<ReqT, RespT> call,
			Metadata headers, ServerCallHandler<ReqT, RespT> next, String header) {
		String bearerToken = header.substring(BEARER.length());
		// if a token is present, it must be valid regardless of whether the endpoint
		// requires authorization or not
		T token;
		try {
		
		    token = verifier.verify(bearerToken); // note: can return null
		    if (token != null) {
		        List<GrantedAuthority> authorities = authorityMapper.getGrantedAuthorities(token);

		        Map<String, Object> claims = extractor.getClaims(token);
		        
		        Serializable details = detailsMapper.getDetails(call, claims);
		        Serializable principal = principalMapper.getPrincipal(claims);

		        JwtAuthenticationToken jwtAuthenticationToken = new JwtAuthenticationToken(claims, bearerToken, authorities, principal, details);

		        Context context = Context.current().withValue(GrpcAuthorization.SECURITY_CONTEXT_AUTHENTICATION, jwtAuthenticationToken);

		        if (mdcMapper != null) {
		            context = context.withValue(GrpcAuthorization.SECURITY_CONTEXT_MDC, mdcMapper.getContext(token));
		        }

		        return Contexts.interceptCall(context, call, headers, next); // sets the new context, then clears it again before returning
		    } else {
		        // do not use a high log level, assume garbage request from the internet
		        log.debug("Unable to verify token");

		        call.close(Status.UNAUTHENTICATED.withDescription("Invalid authorization header"), new Metadata());
		        return new Listener<ReqT>() {
		        };
		    }
		} catch (JwtClientException | JwksClientException e) { // assume client misconfiguration
		    log.debug("JWT verification failed due to {}", e.getMessage());

		    call.close(Status.UNAUTHENTICATED.withDescription(e.getMessage()).withCause(e), new Metadata());
		    return new Listener<ReqT>() {
		    };
		} catch (JwksException | JwtException e) {  // assume server issue
		    // technically we should only see JwksServiceException or JwtServiceException here
		    // but use superclass to catch all

		    log.warn("Unable to process token", e);

		    call.close(Status.UNAVAILABLE.withDescription(e.getMessage()).withCause(e), new Metadata());
		    return new Listener<ReqT>() {
		    };
		}
	}

}