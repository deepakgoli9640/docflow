package com.hpg.console.web;

import com.hpg.console.security.ConsoleUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller
public class HomeController {

    /** Each role starts where its work is: hospitals on payments, operations on merchants. */
    @GetMapping("/")
    public String home(@ModelAttribute("user") ConsoleUser user) {
        if (user == null) {
            return "redirect:/login";
        }
        return user.isMerchant() ? "redirect:/payments" : "redirect:/ops/merchants";
    }
}
