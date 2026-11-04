package com.danditon.quest.entity;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
@Table(name = "quest")
public class Quest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private User author;

	@Column(name = "place_name", nullable = false)
	private String placeName;

	@Column(nullable = false)
	private String address;

	@Column(nullable = false)
	private double lat;

	@Column(nullable = false)
	private double lng;

	@Column(name = "longing_text", nullable = false, columnDefinition = "TEXT")
	private String longingText;

	@Column(name = "photo_url", length = 500)
	private String photoUrl;

	// IMAGE 퀘스트만 저장. CLIP ViT-B-32 512차원, L2 정규화된 벡터
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "photo_embedding", columnDefinition = "json")
	private List<Float> photoEmbedding;

	@Enumerated(EnumType.STRING)
	@Column(name = "quest_type", nullable = false)
	private QuestType questType;

	@Column(name = "quest_title", nullable = false)
	private String questTitle;

	@Column(name = "quest_description", nullable = false, columnDefinition = "TEXT")
	private String questDescription;

	@Column(nullable = false)
	private int difficulty;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private QuestStatus status;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private Quest(User author, String placeName, String address, double lat, double lng,
		String longingText, String photoUrl, List<Float> photoEmbedding, QuestType questType,
		String questTitle, String questDescription, int difficulty) {
		this.author = author;
		this.placeName = placeName;
		this.address = address;
		this.lat = lat;
		this.lng = lng;
		this.longingText = longingText;
		this.photoUrl = photoUrl;
		this.photoEmbedding = photoEmbedding;
		this.questType = questType;
		this.questTitle = questTitle;
		this.questDescription = questDescription;
		this.difficulty = difficulty;
		this.status = QuestStatus.OPEN;
	}

	public static Quest create(User author, String placeName, String address, double lat, double lng,
		String longingText, String photoUrl, List<Float> photoEmbedding, QuestType questType,
		String questTitle, String questDescription, int difficulty) {
		return new Quest(author, placeName, address, lat, lng, longingText, photoUrl, photoEmbedding,
			questType, questTitle, questDescription, difficulty);
	}

}
