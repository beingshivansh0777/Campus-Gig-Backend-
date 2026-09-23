package org.riteshingle.campusgig.Service;

import lombok.RequiredArgsConstructor;
import org.riteshingle.campusgig.Enum.ContractStatus;
import org.riteshingle.campusgig.Exception.*;
import org.riteshingle.campusgig.Model.Contract;
import org.riteshingle.campusgig.Model.Review;
import org.riteshingle.campusgig.Model.UserEntity;
import org.riteshingle.campusgig.Repository.ContractRepository;
import org.riteshingle.campusgig.Repository.ReviewRepository;
import org.riteshingle.campusgig.Repository.UserEntityRepository;
import org.riteshingle.campusgig.RequestDTO.CreateReviewRequest;
import org.riteshingle.campusgig.ResponseDTO.ReviewResponseDTO;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ReviewService {
    private final AuthService authService;
    private final ReviewRepository reviewRepository;
    private final ContractRepository contractRepository;
    private final UserEntityRepository userEntityRepository;
    private final NotificationService notificationService;

    public void postReview(Long contractId, CreateReviewRequest dto){
        UserEntity reviewer = authService.getCurrentProfile();
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract not fond by ID : "+contractId));

        if(!contract.getContractStatus().equals(ContractStatus.COMPLETE))
            throw new InvalidStatusException("Review can only be given after contract is closed");

        UserEntity reviewee;

        if (contract.getClient().getId().equals(reviewer.getId()))
            reviewee = contract.getGig().getUser();
        else if(contract.getGig().getUser().getId().equals(reviewer.getId()))
            reviewee = contract.getClient();
        else throw new ForbiddenException("You are not a participant of this contract");

        boolean alreadyReviewed = reviewRepository.existsByContractIdAndReviewerId(contractId,reviewer.getId());

        if (alreadyReviewed)
            throw new ConflictException("You have already reviewed this contract");

        if (dto.getRating() == null || dto.getRating() < 1 || dto.getRating() > 5)
            throw new BadRequestException("Rating must be between 1 and 5");

        Review review = Review.builder()
                .rating(dto.getRating())
                .comment(dto.getComment())
                .contract(contract)
                .reviewer(reviewer)
                .reviewee(reviewee)
                .build();

        review = reviewRepository.save(review);
        adjustRating(reviewee, review.getRating(), 1);

        notificationService.notify(
                reviewee,
                org.riteshingle.campusgig.Enum.NotificationType.NEW_REVIEW,
                "New Review Received",
                reviewer.getFirstName() + " has given you a " + review.getRating() + "-star review.",
                contractId
        );
    }

    public void deleteReview(Long contractId){
        UserEntity reviewer = authService.getCurrentProfile();
        Contract contract = contractRepository.findById(contractId).orElseThrow(() -> new ResourceNotFoundException("Contract not fond by ID : "+contractId));

        boolean isClient = reviewer.getId().equals(contract.getClient().getId());
        boolean isGig = contract.getGig() != null && reviewer.getId().equals(contract.getGig().getUser().getId());

        if (!isClient && !isGig) {
            throw new ForbiddenException("You aren't a participant of this contract ID : " + contractId);
        }
        Review review = reviewRepository.findByContractIdAndReviewerId(contractId, reviewer.getId()).orElseThrow(() -> new ResourceNotFoundException("Review not found .."));

        if(!contract.getContractStatus().equals(ContractStatus.COMPLETE))
            throw new InvalidStatusException("Review can only be delete after contract is closed");

        adjustRating(review.getReviewee(), -review.getRating(), -1);
        reviewRepository.delete(review);
    }

    public void updateReview(Long contractId, CreateReviewRequest dto){
        UserEntity reviewer = authService.getCurrentProfile();
        Contract contract = contractRepository.findById(contractId).orElseThrow(() -> new ResourceNotFoundException("Contract not found.."));

        // NEW: explicit participant check, consistent with postReview() and deleteReview()
        boolean isClient = reviewer.getId().equals(contract.getClient().getId());
        boolean isGig = contract.getGig() != null && reviewer.getId().equals(contract.getGig().getUser().getId());

        if (!isClient && !isGig) {
            throw new ForbiddenException("You aren't a participant of this contract ID : " + contractId);
        }

        if (!contract.getContractStatus().equals(ContractStatus.COMPLETE))
            throw new InvalidStatusException("Review can only be updated after contract is closed");

        Review review = reviewRepository.findByContractIdAndReviewerId(contractId, reviewer.getId()).orElseThrow(() -> new ResourceNotFoundException("Review not found .."));

        if (dto.getComment() != null && !dto.getComment().isBlank()) {
            review.setComment(dto.getComment());
        }

        if (dto.getRating() != null) {
            // CHANGED: 0 -> 1 to match the actual allowed range
            if (dto.getRating() < 1 || dto.getRating() > 5)
                throw new BadRequestException("Rating must be between 1 and 5");

            Integer oldRating = review.getRating();
            if (!dto.getRating().equals(oldRating)) {
                review.setRating(dto.getRating());
                adjustRating(review.getReviewee(), dto.getRating() - oldRating, 0);
            }
        }
    }

    public List<ReviewResponseDTO> reviews(Pageable pageable){
        UserEntity user = authService.getCurrentProfile();
        List<Review> reviewList = reviewRepository.findByRevieweeId(user.getId(),pageable).getContent();

        return reviewList.stream().map(review -> ReviewResponseDTO.builder()
                .name(review.getReviewer().getFirstName()+" "+review.getReviewer().getLastName())
                .createdAt(review.getCreatedAt())
                .comment(review.getComment() == null ? "" : review.getComment())
                .rating(review.getRating())
                .build()
        ).toList();
    }

    private void adjustRating(UserEntity reviewee, long ratingDelta, long countDelta) {
        long sum = reviewee.getTotalRatingSum() == null ? 0L : reviewee.getTotalRatingSum();
        long count = reviewee.getTotalRatings() == null ? 0L : reviewee.getTotalRatings();

        reviewee.setTotalRatingSum(sum + ratingDelta);
        reviewee.setTotalRatings(count + countDelta);

        userEntityRepository.save(reviewee);
    }

    public Optional<ReviewResponseDTO> getMyReview(Long contractId) {
        UserEntity reviewer = authService.getCurrentProfile();

        return reviewRepository.findByContractIdAndReviewerId(contractId, reviewer.getId())
                .map(review -> ReviewResponseDTO.builder()
                        .name(review.getReviewer().getFirstName() + " " + review.getReviewer().getLastName())
                        .createdAt(review.getCreatedAt())
                        .comment(review.getComment() == null ? "" : review.getComment())
                        .rating(review.getRating())
                        .build());
    }
}
