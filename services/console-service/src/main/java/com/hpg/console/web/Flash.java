package com.hpg.console.web;

import com.hpg.console.client.ApiException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Messages carried across a redirect, so a form post that fails returns to the page the person was on
 * with an explanation, rather than dumping them on an error screen.
 */
public final class Flash {

    public static final String SUCCESS = "flashSuccess";
    public static final String ERROR = "flashError";

    private Flash() {
    }

    public static void success(RedirectAttributes attributes, String message) {
        attributes.addFlashAttribute(SUCCESS, message);
    }

    public static void error(RedirectAttributes attributes, String message) {
        attributes.addFlashAttribute(ERROR, message);
    }

    /** Shows the API's own sentence, plus any field-level detail it sent. */
    public static void error(RedirectAttributes attributes, ApiException e) {
        StringBuilder message = new StringBuilder(e.problem().detail());
        if (e.problem().violations() != null && !e.problem().violations().isEmpty()) {
            message.append(" (");
            for (int i = 0; i < e.problem().violations().size(); i++) {
                ApiException.Problem.Violation violation = e.problem().violations().get(i);
                message.append(i > 0 ? "; " : "").append(violation.field()).append(' ').append(violation.message());
            }
            message.append(')');
        }
        attributes.addFlashAttribute(ERROR, message.toString());
    }
}
