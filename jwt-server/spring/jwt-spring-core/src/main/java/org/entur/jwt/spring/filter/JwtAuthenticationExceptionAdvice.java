package org.entur.jwt.spring.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@ControllerAdvice
public class JwtAuthenticationExceptionAdvice extends ResponseEntityExceptionHandler  {

	final static Logger logger = LoggerFactory.getLogger(JwtAuthenticationExceptionAdvice.class);

	@ExceptionHandler(JwtAuthenticationServiceUnavailableException.class)
	public ResponseEntity<?> handleHttpRequestMethodNotSupportedException(JwtAuthenticationServiceUnavailableException ex, WebRequest request) {
		logger.warn("Unable to verify token, returning service unavailable", ex);
		
		return new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE);
	}

}
