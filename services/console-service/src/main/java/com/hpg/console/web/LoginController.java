package com.hpg.console.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
            @RequestParam(required = false) String loggedOut, Model model) {
        if (error != null) {
            model.addAttribute("error", "Those credentials were not accepted.");
        }
        if (loggedOut != null) {
            model.addAttribute("info", "You have been signed out.");
        }
        return "login";
    }
}
