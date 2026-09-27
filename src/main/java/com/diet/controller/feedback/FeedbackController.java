package com.diet.controller.feedback;

import com.diet.model.web.FeedbackRequest;
import com.diet.security.CurrentUser;
import com.diet.service.feedback.FeedbackService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/diet/feedback")
public class FeedbackController {
    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /** 身份取自 JWT subject（第四周鉴权），归属校验由 service 层按 userId 完成。 */
    @PostMapping
    public void save(@RequestBody FeedbackRequest request) {
        feedbackService.save(CurrentUser.id(), request);
    }
}
