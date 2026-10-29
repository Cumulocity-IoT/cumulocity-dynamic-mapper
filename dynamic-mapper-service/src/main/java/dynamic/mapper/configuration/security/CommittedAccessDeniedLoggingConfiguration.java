/*
 * Copyright (c) 2026 Cumulocity GmbH
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @authors Stefan Witschel
 */

package dynamic.mapper.configuration.security;

import java.io.IOException;
import java.util.EnumSet;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Replaces Tomcat's noisy container-level stack trace for one specific, harmless race with a
 * single, meaningful log line.
 *
 * <p>Some requests are re-dispatched internally (Spring Security's filters re-run on the ASYNC
 * dispatch, and Boot's error-page machinery re-dispatches to {@code /error} on ERROR). If
 * authorization is denied on such a re-dispatch after the response has already been committed,
 * {@code ExceptionTranslationFilter} cannot send a clean 403 and instead rethrows, which Tomcat's
 * {@code StandardHostValve} logs as {@code "Unable to handle the Spring Security Exception because
 * the response is already committed"} with a ~70-frame stack trace and no request context.
 *
 * <p>This filter sits in front of that chain (see {@link #committedAccessDeniedFilterRegistration()}
 * for why) and intercepts exactly that failure mode, logging the denied path once instead. Any
 * other exception is rethrown unchanged, so genuine errors are never hidden.
 */
@Slf4j
@Configuration
public class CommittedAccessDeniedLoggingConfiguration {

    @Bean
    public FilterRegistrationBean<CommittedAccessDeniedFilter> committedAccessDeniedFilterRegistration() {
        FilterRegistrationBean<CommittedAccessDeniedFilter> registration = new FilterRegistrationBean<>(
                new CommittedAccessDeniedFilter());
        // Must run on REQUEST, ASYNC and ERROR dispatch to see the re-dispatches the race happens on.
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
        // Must run before Spring Security's own filter chain (Boot registers it at order -100) so an
        // exception it rethrows is caught here instead of propagating to the servlet container.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Slf4j
    static class CommittedAccessDeniedFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            try {
                filterChain.doFilter(request, response);
            } catch (ServletException ex) {
                if (response.isCommitted() && ex.getCause() instanceof AccessDeniedException accessDenied) {
                    log.warn(
                            "Access denied for {} {} ({} dispatch) but the response was already committed, "
                                    + "so no 403 could be sent to the client: {}",
                            request.getMethod(), request.getRequestURI(), request.getDispatcherType(),
                            accessDenied.getMessage());
                    return;
                }
                throw ex;
            }
        }

        // Defaults to true (skip); override so the above actually runs on these re-dispatches.
        @Override
        protected boolean shouldNotFilterAsyncDispatch() {
            return false;
        }

        @Override
        protected boolean shouldNotFilterErrorDispatch() {
            return false;
        }
    }
}
