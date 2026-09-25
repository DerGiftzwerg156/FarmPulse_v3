package de.farmpulse.rpsim.support;

import de.farmpulse.rpsim.domain.Character;
import de.farmpulse.rpsim.domain.CharacterCategory;
import de.farmpulse.rpsim.domain.CharacterStatus;
import de.farmpulse.rpsim.domain.Savegame;
import de.farmpulse.rpsim.repository.CharacterRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/** Static access to repositories for end-to-end scenario helpers. */
@Component
public class E2EHelper implements ApplicationContextAware {

    private static ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        ctx = applicationContext;
    }

    public static Character firstDynamic(Savegame sg) {
        return ctx.getBean(CharacterRepository.class)
                .findBySavegameAndCategoryAndStatus(sg, CharacterCategory.DYNAMIC, CharacterStatus.ACTIVE).get(0);
    }
}
