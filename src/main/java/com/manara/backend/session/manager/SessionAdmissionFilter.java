package com.manara.backend.session.manager;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.core.Ordered;
import org.springframework.security.web.util.OnCommittedResponseWrapper;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Counts a newly established session against its account's ceiling at the first moment the session
 * exists in the store — and before any of the response carrying its cookie has left the server.
 *
 * <p>Spring Session's {@link SessionRepositoryFilter} stores the session either when the response is
 * committed or, if nothing committed it, as the request unwinds. This filter sits immediately outside
 * that one and so sees both moments just after Spring Session has acted on them: a commit through the
 * response wrapper below, which Spring Session's own wrapper passes the commit on to only once it has
 * saved the session, and the unwinding in the {@code finally}. Either way the client receives its new
 * cookie only after {@link SessionCeiling} has applied the ceiling to it.
 *
 * <p>The container installs this filter from the bean, at {@link #getOrder()}, the same way it
 * installs Spring Session's filter at {@link SessionRepositoryFilter#DEFAULT_ORDER}.
 */
@Component
@RequiredArgsConstructor
public class SessionAdmissionFilter extends OncePerRequestFilter implements Ordered {

    private final SessionCeiling sessionCeiling;

    /** One step outside Spring Session's filter, so that it has stored the session before this runs. */
    @Override
    public int getOrder() {
        return SessionRepositoryFilter.DEFAULT_ORDER - 1;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, new AdmitOnCommit(request, response));
        } finally {
            sessionCeiling.admitPending(request);
        }
    }

    private final class AdmitOnCommit extends OnCommittedResponseWrapper {

        private final HttpServletRequest request;

        private AdmitOnCommit(HttpServletRequest request, HttpServletResponse response) {
            super(response);
            this.request = request;
        }

        @Override
        protected void onResponseCommitted() {
            sessionCeiling.admitPending(request);
        }
    }
}
