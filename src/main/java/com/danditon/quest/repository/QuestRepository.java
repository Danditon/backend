package com.danditon.quest.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.danditon.quest.entity.Quest;
import com.danditon.quest.entity.QuestStatus;

public interface QuestRepository extends JpaRepository<Quest, Long> {

	List<Quest> findByStatus(QuestStatus status);

}
