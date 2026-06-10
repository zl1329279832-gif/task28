package com.example.points.controller;

import com.example.points.common.ApiResponse;
import com.example.points.dto.ReviewRequest;
import com.example.points.entity.ReviewOrder;
import com.example.points.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @GetMapping("/pending")
    public ApiResponse<List<ReviewOrder>> listPending() {
        List<ReviewOrder> orders = reviewService.listPendingReviews();
        return ApiResponse.ok(orders);
    }

    @GetMapping("/{reviewNo}")
    public ApiResponse<ReviewOrder> getReview(@PathVariable String reviewNo) {
        ReviewOrder order = reviewService.getReview(reviewNo);
        return ApiResponse.ok(order);
    }

    @PostMapping("/execute")
    public ApiResponse<Void> review(@Valid @RequestBody ReviewRequest request) {
        reviewService.review(request);
        return ApiResponse.ok();
    }
}
