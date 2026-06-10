package com.example.points.service;

import com.example.points.dto.ReviewRequest;
import com.example.points.entity.ReviewOrder;

import java.util.List;

public interface ReviewService {

    List<ReviewOrder> listPendingReviews();

    ReviewOrder getReview(String reviewNo);

    void review(ReviewRequest request);
}
