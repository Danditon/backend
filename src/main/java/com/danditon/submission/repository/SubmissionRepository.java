package com.danditon.submission.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.danditon.submission.entity.Submission;
import com.danditon.submission.entity.VerifyStatus;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

	interface QuestPassedCount {
		Long getQuestId();
		long getPassedCount();
	}

	@Query("select s.quest.id as questId, count(s) as passedCount from Submission s "
		+ "where s.quest.id in :questIds and s.verifyStatus = :status group by s.quest.id")
	List<QuestPassedCount> countByQuestIdsAndStatus(@Param("questIds") List<Long> questIds,
		@Param("status") VerifyStatus status);

	@EntityGraph(attributePaths = "quest")
	List<Submission> findByPerformerIdAndVerifyStatusOrderByCreatedAtDescIdDesc(Long performerId,
		VerifyStatus verifyStatus);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Submission s where s.id = :id")
	Optional<Submission> findByIdForUpdate(@Param("id") Long id);

	boolean existsByQuestIdAndPerformerIdAndVerifyStatus(Long questId, Long performerId, VerifyStatus verifyStatus);

	boolean existsByQuestIdAndPerformerId(Long questId, Long performerId);

	List<Submission> findByQuestIdAndVerifyStatusOrderByCreatedAtDesc(Long questId, VerifyStatus verifyStatus);

	List<Submission> findByQuestIdAndPerformerIdOrderByCreatedAtDesc(Long questId, Long performerId);

}
