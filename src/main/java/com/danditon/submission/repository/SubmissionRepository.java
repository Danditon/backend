package com.danditon.submission.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.danditon.submission.entity.Submission;
import com.danditon.submission.entity.VerifyStatus;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

	boolean existsByQuestIdAndPerformerIdAndVerifyStatus(Long questId, Long performerId, VerifyStatus verifyStatus);

	boolean existsByQuestIdAndPerformerId(Long questId, Long performerId);

	List<Submission> findByQuestIdAndVerifyStatusOrderByCreatedAtDesc(Long questId, VerifyStatus verifyStatus);

	List<Submission> findByQuestIdAndPerformerIdOrderByCreatedAtDesc(Long questId, Long performerId);

}
