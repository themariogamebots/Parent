package org.themarioga.telegram.sh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.themarioga.commons.engine.models.User;
import org.themarioga.commons.engine.services.intf.UserService;
import org.themarioga.telegram.sh.services.intf.TelegramGameService;
import org.themarioga.telegram.sh.support.PlayedGameTest;

import java.util.List;

/**
 * Los comandos de administración: borrar la partida propia, borrar la de otro, borrarlas todas,
 * difundir un mensaje y cortar la difusión.
 * <p>
 * Lo que se vigila aquí son los dos bordes que tienen: que quien no administra no pueda usarlos
 * —cinco comandos que borran partidas ajenas y escriben a toda la base de datos— y que la difusión
 * no se cargue a los usuarios cuando el mensaje no vale. Un envío que Telegram rechaza marca el
 * chat como inactivo, así que un mensaje vacío daría de baja a todo el mundo de una tacada.
 */
class AdminFlowTest extends PlayedGameTest {

    private static final long GROUP_CHAT = -100900L;
    private static final long CREATOR = 900L;
    private static final List<Long> OTHERS = List.of(901L, 902L, 903L, 904L);

    private static final long OTHER_GROUP_CHAT = -100910L;
    private static final long OTHER_CREATOR = 910L;

    @Autowired
    private UserService userService;
    @Autowired
    private TelegramGameService telegramGameService;

    @Override
    protected long groupChat() {
        return GROUP_CHAT;
    }

    @Override
    protected long creator() {
        return CREATOR;
    }

    @Override
    protected List<Long> others() {
        return OTHERS;
    }

    @BeforeEach
    void setUp() {
        givenRegisteredUser(ADMIN_TELEGRAM_ID, "admin");

        startGame();
        messages.clear();
    }

    // ///////////// Borrado //////////////////

    @Test
    void theCreatorCanDeleteTheirOwnGameFromPrivate() {
        logInAs(CREATOR, null, "private");

        bot.deleteMyGames();

        Assertions.assertNull(currentGame(), "la partida se borra");
        Assertions.assertTrue(messages.sentTo(GROUP_CHAT).stream().anyMatch(sent -> sent.text().contains("Se ha borrado la partida")), "y se avisa en el grupo");
    }

    @Test
    void deletingYourGamesWithoutAnySaysSo() {
        logInAs(OTHERS.get(0), null, "private");

        bot.deleteMyGames();

        Assertions.assertNotNull(currentGame(), "la partida de otro no se toca");
        Assertions.assertTrue(messages.sentTo(OTHERS.get(0)).stream().anyMatch(sent -> sent.text().contains("No hay ninguna partida")), () -> "hay que decírselo: " + messages.sentTo(OTHERS.get(0)));
    }

    /**
     * El alias llega tecleado por una persona, así que puede venir con arroba y en cualquier
     * combinación de mayúsculas.
     */
    @Test
    void anAdminCanDeleteSomeoneElsesGameByUsername() {
        logInAs(ADMIN_TELEGRAM_ID, null, "private");

        bot.deleteGameByCreatorUsername("@Creador");

        Assertions.assertNull(currentGame(), "la partida del otro se borra");
        Assertions.assertTrue(messages.sentTo(ADMIN_TELEGRAM_ID).stream().anyMatch(sent -> sent.text().contains("@Creador")), "y se avisa a quien administra");
    }

    @Test
    void aPlainUserCannotDeleteSomeoneElsesGame() {
        logInAs(OTHERS.get(0), null, "private");

        bot.deleteGameByCreatorUsername("creador");

        Assertions.assertNotNull(currentGame(), "la partida sigue ahí");
    }

    @Test
    void anAdminCanDeleteEveryGameAtOnce() {
        givenSecondGame();

        Assertions.assertEquals(2, telegramGameService.getAll().size());

        logInAs(ADMIN_TELEGRAM_ID, null, "private");

        bot.deleteAllGames();

        Assertions.assertTrue(telegramGameService.getAll().isEmpty(), "no queda ninguna");
        Assertions.assertTrue(messages.sentTo(CREATOR).stream().anyMatch(sent -> sent.text().contains("borrada por la administración")), "hay que avisar a cada creador");
        Assertions.assertTrue(messages.sentTo(OTHER_CREATOR).stream().anyMatch(sent -> sent.text().contains("borrada por la administración")), "a los dos");
    }

    @Test
    void aPlainUserCannotDeleteEveryGame() {
        logInAs(OTHERS.get(0), null, "private");

        bot.deleteAllGames();

        Assertions.assertEquals(1, telegramGameService.getAll().size(), "la partida sigue ahí");
    }

    // ///////////// Difusión //////////////////

    @Test
    void anAdminReachesEveryUserAndEveryRoom() {
        logInAs(ADMIN_TELEGRAM_ID, null, "private");

        bot.sendMessageToEveryone("Mañana hay mantenimiento");

        for (long telegramId : OTHERS) {
            Assertions.assertTrue(messages.sentTo(telegramId).stream().anyMatch(sent -> sent.text().equals("Mañana hay mantenimiento")), () -> "le falta el aviso a " + telegramId);
        }

        Assertions.assertTrue(messages.sentTo(GROUP_CHAT).stream().anyMatch(sent -> sent.text().equals("Mañana hay mantenimiento")), "y al grupo también");
        Assertions.assertTrue(messages.sentTo(ADMIN_TELEGRAM_ID).stream().anyMatch(sent -> sent.text().contains("Se han enviado todos los mensajes")), "y hay que confirmarlo");
    }

    /**
     * El borde caro: un mensaje que Telegram rechazaría se para antes de salir, porque cada rechazo
     * marca el chat como inactivo y la difusión los recorre todos.
     */
    @Test
    void anEmptyOrOverlongBroadcastIsNotSentToAnyone() {
        logInAs(ADMIN_TELEGRAM_ID, null, "private");

        bot.sendMessageToEveryone("   ");
        bot.sendMessageToEveryone("x".repeat(4097));

        for (long telegramId : OTHERS) {
            Assertions.assertTrue(messages.sentTo(telegramId).isEmpty(), () -> "no le tiene que llegar nada a " + telegramId);
        }

        Assertions.assertEquals(2, messages.sentTo(ADMIN_TELEGRAM_ID).size(), "pero a quien administra se le dice las dos veces");

        for (User user : userService.getAllUsers()) {
            Assertions.assertTrue(user.getActive(), () -> "nadie puede quedarse desactivado: " + user.getUsername());
        }
    }

    @Test
    void aPlainUserCannotBroadcast() {
        logInAs(OTHERS.get(0), null, "private");

        bot.sendMessageToEveryone("hola a todos");

        Assertions.assertTrue(messages.sent().stream().noneMatch(sent -> sent.text().equals("hola a todos")), "no sale de aquí");
    }

    @Test
    void theBroadcastSwitchStopsTheNextBroadcast() {
        logInAs(ADMIN_TELEGRAM_ID, null, "private");

        bot.toggleGlobalMessages();
        bot.sendMessageToEveryone("no debería salir");

        Assertions.assertTrue(messages.sent().stream().noneMatch(sent -> sent.text().equals("no debería salir")));

        bot.toggleGlobalMessages();
        bot.sendMessageToEveryone("ahora sí");

        Assertions.assertTrue(messages.sentTo(OTHERS.get(0)).stream().anyMatch(sent -> sent.text().equals("ahora sí")));
    }

    @Test
    void aPlainUserCannotFlipTheBroadcastSwitch() {
        logInAs(OTHERS.get(0), null, "private");

        bot.toggleGlobalMessages();

        // Si el interruptor se hubiera movido, la difusión siguiente no saldría
        logInAs(ADMIN_TELEGRAM_ID, null, "private");
        bot.sendMessageToEveryone("sigue encendido");

        Assertions.assertTrue(messages.sentTo(OTHERS.get(0)).stream().anyMatch(sent -> sent.text().equals("sigue encendido")));
    }

    // ///////////// Apoyo //////////////////

    /** Una segunda partida, en otro grupo y de otro creador, para lo que borra a lo ancho. */
    private void givenSecondGame() {
        givenRegisteredUser(OTHER_CREATOR, "otrocreador");

        logInAs(OTHER_CREATOR, OTHER_GROUP_CHAT, "group");
        bot.startCreatingGame(OTHER_GROUP_CHAT, "Otro grupo");

        messages.clear();
    }

}
