package com.danditon.quest.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.danditon.quest.entity.Quest;

public interface QuestRepository extends JpaRepository<Quest, Long> {

}
