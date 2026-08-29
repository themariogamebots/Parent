package org.themarioga.telegram.sh;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.themarioga.commons.engine.models.Lang;
import org.themarioga.commons.engine.services.intf.I18NService;
import org.themarioga.commons.engine.services.intf.LanguageService;

import java.util.List;

/**
 * Comprueba que el baseline de Flyway y el modelo de entidades dicen lo mismo.
 * <p>
 * El trabajo lo hace {@code spring.jpa.hibernate.ddl-auto=validate}: si al esquema le falta una
 * columna, le sobra, o un tipo no cuadra, el contexto no arranca y el test falla. Es la red que
 * evita que el baseline se quede atrás cuando alguien toque una entidad.
 */
@SpringBootTest
class SchemaBaselineTest {

    /** 59 textos comunes traídos de CAH-Telegram + 86 propios de Secret Hitler. */
    private static final int EXPECTED_TAGS = 145;

    @Autowired
    private LanguageService languageService;
    @Autowired
    private I18NService i18NService;
    @Autowired
    private EntityManager entityManager;

    private long countTags(String lang) {
        return entityManager.createQuery("SELECT COUNT(t) FROM Tag t WHERE t.lang.id = :lang", Long.class).setParameter("lang", lang).getSingleResult();
    }

    @Test
    void schemaMatchesTheEntityModel() {
        // Si hemos llegado aquí, Flyway migró y Hibernate validó el esquema resultante.
        Assertions.assertNotNull(languageService);
    }

    /**
     * Las tres tablas propias de esta capa. Si el baseline se regenerase sin ellas, Hibernate no se
     * quejaría hasta la primera partida.
     */
    @Test
    void theTelegramTablesExist() {
        for (String table : List.of("telegram_room", "telegram_game", "telegram_player", "telegram_user")) {
            Object count = entityManager.createNativeQuery("SELECT COUNT(*) FROM " + table).getSingleResult();

            Assertions.assertNotNull(count, () -> "falta la tabla " + table);
        }
    }

    /**
     * Y las del motor de Secret Hitler, que en esta base de datos son las que se llaman game/player/
     * round: aquí no conviven con las de CAH porque cada despliegue tiene su base de datos (D1).
     */
    @Test
    void theEngineTablesExist() {
        for (String table : List.of("game", "player", "round", "law", "game_law_pick_deck", "game_law_discard_deck", "round_available_laws", "round_votes")) {
            Object count = entityManager.createNativeQuery("SELECT COUNT(*) FROM " + table).getSingleResult();

            Assertions.assertNotNull(count, () -> "falta la tabla " + table);
        }
    }

    @Test
    void languagesAreSeeded() {
        List<Lang> langs = languageService.getLangs();

        Assertions.assertEquals(2, langs.size());
        Assertions.assertTrue(langs.stream().anyMatch(l -> "es".equals(l.getId())));
        Assertions.assertTrue(langs.stream().anyMatch(l -> "en".equals(l.getId())));
        Assertions.assertEquals("es", languageService.getDefaultLanguage().getId());
    }

    @Test
    void commonTagsAreSeededInBothLanguages() {
        Assertions.assertEquals("← Volver", i18NService.get("GO_BACK", "es"));
        Assertions.assertEquals("← Go back", i18NService.get("GO_BACK", "en"));

        String welcome = i18NService.get("PLAYER_WELCOME", "es");
        Assertions.assertNotEquals("PLAYER_WELCOME", welcome, "el tag no está en la tabla");
        Assertions.assertTrue(welcome.contains("\n"), "los \\n del SQL deben llegar como saltos de línea");
    }

    /**
     * Los textos del juego. {@code I18NService} devuelve el nombre del tag cuando no lo encuentra,
     * así que comparar contra el nombre es la forma de detectar que falta.
     */
    @Test
    void gameTagsArePresent() {
        for (String tag : List.of("SH_ROLE_LIBERAL", "SH_ROLE_FASCIST", "SH_ROLE_HITLER", "SH_ROLE_FELLOW_FASCISTS", "SH_BOARD", "SH_PRESIDENT_SELECT_CHANCELLOR", "SH_CHANCELLOR_NOMINATED", "SH_VOTE_JA", "SH_VOTE_NEIN", "SH_PRESIDENT_DISCARD", "SH_CHANCELLOR_ENACT", "SH_LAW_AUTO_ENACTED", "SH_VETO_PROPOSED", "SH_POWER_INVESTIGATE_RESULT", "SH_POWER_EXECUTION_SELECT", "SH_WIN_LIBERAL_LAWS", "SH_WIN_FASCIST_LAWS", "SH_WIN_HITLER_EXECUTED", "SH_WIN_HITLER_CHANCELLOR", "SH_NEXT_ROUND_BUTTON")) {
            for (String lang : List.of("es", "en")) {
                Assertions.assertNotEquals(tag, i18NService.get(tag, lang), () -> "falta el tag " + tag + " en " + lang);
            }
        }
    }

    /**
     * Un tag por cada valor de {@code SHErrorEnum}, con el nombre que espera
     * {@code ErrorMessageResolver}. Sin ellos, un error de juego se le enseñaría al usuario como
     * "Ha ocurrido un error inesperado".
     */
    @Test
    void engineErrorTagsArePresent() {
        for (String tag : List.of("ERROR_ROUND_NOT_FOUND", "ERROR_ROUND_NOT_STARTED", "ERROR_ROUND_NOT_ENDING", "ERROR_ROUND_WRONG_STATUS", "ERROR_PLAYER_ALREADY_VOTED", "ERROR_PLAYER_ALREADY_DEAD", "ERROR_PLAYER_CANNOT_BE_KILLED", "ERROR_PLAYER_NOT_VALID_CHANCELLOR_CANDIDATE", "ERROR_LAW_NOT_FOUND", "ERROR_PLAYER_CANNOT_BE_INVESTIGATED", "ERROR_PLAYER_CANNOT_BE_ELECTED", "ERROR_VETO_NOT_ACTIVE", "ERROR_PLAYER_CANNOT_PERFORM_ACTION", "UNKNOWN_ERROR")) {
            for (String lang : List.of("es", "en")) {
                Assertions.assertNotEquals(tag, i18NService.get(tag, lang), () -> "falta el tag " + tag + " en " + lang);
            }
        }
    }

    /**
     * Los dos idiomas tienen que estar completos: si a uno le falta un tag, el usuario que lo tenga
     * configurado ve el nombre del tag donde debería ir el texto.
     */
    @Test
    void bothLanguagesHaveTheSameTags() {
        Assertions.assertEquals(EXPECTED_TAGS, countTags("es"));
        Assertions.assertEquals(EXPECTED_TAGS, countTags("en"));
    }

}
