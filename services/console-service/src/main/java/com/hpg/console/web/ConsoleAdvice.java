package com.hpg.console.web;

import com.hpg.console.ConsoleProperties;
import com.hpg.console.client.ApiException;
import com.hpg.console.security.ConsoleAuthentication;
import com.hpg.console.security.ConsoleUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.ModelAndView;

/** Things every page needs, and what to show when an API says no. */
@ControllerAdvice
public class ConsoleAdvice {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAdvice.class);

    private final ConsoleProperties properties;

    public ConsoleAdvice(ConsoleProperties properties) {
        this.properties = properties;
    }

    /** The signed-in person, for the header and the navigation. */
    @ModelAttribute("user")
    public ConsoleUser user(Authentication authentication) {
        return authentication instanceof ConsoleAuthentication console ? console.user() : null;
    }

    /**
     * The path being rendered, so the navigation can mark the current section.
     *
     * <p>Thymeleaf 3.1 removed {@code #httpServletRequest} and friends from templates on purpose: a
     * template that reaches into the request tends to grow logic that belongs in a controller. The
     * controller passes in what the view needs instead.
     */
    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute("liveRefreshSeconds")
    public long liveRefreshSeconds() {
        return properties.liveRefresh().toSeconds();
    }

    /**
     * An API refused something on a page load. The service's own wording is shown, because it is more
     * accurate than anything this console could invent ("Maximum amount per payment is 200000.00 INR"
     * beats "something went wrong").
     */
    @ExceptionHandler(ApiException.class)
    public ModelAndView apiError(ApiException e) {
        log.info("API returned {} {}", e.problem().status(), e.problem().errorCode());
        ModelAndView view = new ModelAndView("error");
        view.addObject("problem", e.problem());
        view.setStatus(HttpStatus.valueOf(e.problem().status() >= 500 ? 502 : e.problem().status()));
        return view;
    }
}
