package org.themarioga.telegram.sh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.themarioga.commons.telegram.services.intf.ApplicationService;

import java.util.Set;

/**
 * Fija los nombres de comando y las claves de callback.
 * <p>
 * Todavía no hay nada desplegado, así que hoy este test no protege ninguna partida en curso: se
 * escribe ahora precisamente para que no haga falta escribirlo cuando ya duela. Telegram guarda los
 * botones dentro de los mensajes indefinidamente, así que desde el primer despliegue renombrar una
 * clave rompe las partidas que estén a medias. En CAH-Telegram el equivalente se escribió después,
 * cuando ya había bots en producción.
 */
@SpringBootTest(properties = {"sh.bot.enabled=true", "sh.bot.token=111:fake-token-de-pruebas", "sh.bot.name=shtestbot"})
class SHApplicationServiceTest {

    @Autowired
    @Qualifier("shBotApplicationService")
    private ApplicationService applicationService;

    private static final Set<String> COMMANDS = Set.of(
            "/start", "/lang", "/create", "/help", "/deletemygames", "/deletegamebyusername", "/deleteallgames", "/sendmessagetoeveryone", "/toggleglobalmessages");

    private static final Set<String> CALLBACKS = Set.of(
            "change_user_lang", "game_menu", "game_configure", "game_sel_max_players", "game_change_max_players", "game_sel_kick", "game_kick", "game_join", "game_leave", "game_start", "sh_chancellor", "sh_vote", "sh_discard", "sh_enact", "sh_veto", "sh_veto_resolve", "sh_action", "sh_investigate", "sh_special_election", "sh_kill", "sh_next_round", "game_delete_group", "game_delete_private");

    @Test
    void theCommandInterfaceIsUnchanged() {
        Assertions.assertEquals(COMMANDS, applicationService.getBotCommands().keySet());
    }

    @Test
    void theCallbackInterfaceIsUnchanged() {
        Assertions.assertEquals(CALLBACKS, applicationService.getCallbackQueries().keySet());
    }

    @Test
    void everyHandlerIsWired() {
        for (String command : COMMANDS) {
            Assertions.assertNotNull(applicationService.getBotCommands().get(command), () -> "falta " + command);
        }
        for (String callback : CALLBACKS) {
            Assertions.assertNotNull(applicationService.getCallbackQueries().get(callback), () -> "falta " + callback);
        }
    }

    /**
     * El dispatcher parte la clave por "__", así que una clave que lleve "__" dentro nunca se
     * encontraría en el mapa.
     */
    @Test
    void noKeyCollidesWithThePayloadSeparator() {
        for (String key : CALLBACKS) {
            Assertions.assertFalse(key.contains("__"), () -> "la clave " + key + " colisiona con el separador de datos");
        }
    }

}
