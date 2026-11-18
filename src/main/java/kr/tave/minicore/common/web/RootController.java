package kr.tave.minicore.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class RootController {

    @GetMapping("/")
    public String swagger() {
        return "redirect:/swagger-ui/index.html";
    }
}
