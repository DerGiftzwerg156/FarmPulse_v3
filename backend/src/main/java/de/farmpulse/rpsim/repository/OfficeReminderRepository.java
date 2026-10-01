package de.farmpulse.rpsim.repository;

import de.farmpulse.rpsim.domain.OfficeReminder;
import de.farmpulse.rpsim.domain.Savegame;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficeReminderRepository extends JpaRepository<OfficeReminder, Long> {

    boolean existsBySavegameAndSubjectTypeAndSubjectIdAndDeadlineGameTime(Savegame savegame, String subjectType,
                                                                          long subjectId, long deadlineGameTime);
}
