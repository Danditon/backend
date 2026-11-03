package com.danditon.submission.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.danditon.submission.entity.Submission;

public interface SubmissionRepository extends JpaRepository<Submission, Long> {

}
