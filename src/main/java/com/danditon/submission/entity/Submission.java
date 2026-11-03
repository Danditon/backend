package com.danditon.submission.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.danditon.quest.entity.Quest;
import com.danditon.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "submission")
public class Submission {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "quest_id", nullable = false)
	private Quest quest;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "performer_id", nullable = false)
	private User performer;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String greeting;

	@Column(name = "photo_url", length = 500)
	private String photoUrl;

	@Enumerated(EnumType.STRING)
	@Column(name = "verify_status", nullable = false)
	private VerifyStatus verifyStatus;

	// IMAGE 퀘스트만 기록 (GENERAL은 null)
	private Double similarity;

	// JPA Boolean -> MySQL BIT(1)
	@Column(name = "is_liked", nullable = false)
	private Boolean isLiked;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Submission(Quest quest, User performer, String greeting, String photoUrl,
		VerifyStatus verifyStatus, Double similarity) {
		this.quest = quest;
		this.performer = performer;
		this.greeting = greeting;
		this.photoUrl = photoUrl;
		this.verifyStatus = verifyStatus;
		this.similarity = similarity;
		this.isLiked = false;
	}

	public static Submission create(Quest quest, User performer, String greeting, String photoUrl,
		VerifyStatus verifyStatus, Double similarity) {
		return new Submission(quest, performer, greeting, photoUrl, verifyStatus, similarity);
	}

	public void toggleLike() {
		this.isLiked = !this.isLiked;
	}

}
