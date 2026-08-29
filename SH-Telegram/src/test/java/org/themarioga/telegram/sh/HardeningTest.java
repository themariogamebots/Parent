package org.themarioga.telegram.sh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.themarioga.commons.telegram.util.TelegramUserUtils;
import org.themarioga.telegram.sh.support.PlayedGameTest;
import org.themarioga.telegram.sh.support.RecordingBotMessageService;

import java.util.List;

/**
 * Los límites de Telegram y los jugadores raros, contra la mesa más grande que admite el juego.
 * <p>
 * Son cosas que no rompen ningún test de flujo pero sí una partida de verdad: un mensaje de más de
 * 4096 caracteres, una fila de teclado de más de ocho botones o un jugador sin alias que se pinta
 * como un hueco en blanco. Con diez jugadores es donde más cerca se está de todas ellas.
 */
class HardeningTest extends PlayedGameTest {

    /** Lo que admite {@code sendMessage}. */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    /** Lo que Telegram deja poner en una fila de teclado en línea. */
    private static final int MAX_BUTTONS_PER_ROW = 8;

    private static final long GROUP_CHAT = -101000L;
    private static final long CREATOR = 1000L;
    private static final List<Long> OTHERS = List.of(1001L, 1002L, 1003L, 1004L, 1005L, 1006L, 1007L, 1008L, 1009L);

    /** El de esta mesa que no tiene alias de Telegram: tiene nombre, pero no @arroba. */
    private static final long NO_ALIAS = 1009L;

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

    @Override
    protected void registerPlayers() {
        givenRegisteredUser(creator(), "creador");

        for (long other : others()) {
            if (other == NO_ALIAS) {
                givenRegisteredUserWithoutAlias(other, "Sinalias");
            } else {
                givenRegisteredUser(other, "jugador" + other);
            }
        }
    }

    @BeforeEach
    void setUp() {
        startGame();
    }

    /**
     * Una partida entera con la mesa llena, que es donde los mensajes generados son más largos: el
     * revelado final de roles lleva una línea por jugador.
     */
    @Test
    void noGeneratedMessageEverExceedsWhatTelegramAccepts() {
        enactFascistLaws(gameConfig.getFascistLawsToWin());

        Assertions.assertNull(currentGame(), "la partida llega hasta el final");

        for (RecordingBotMessageService.Sent sent : messages.sent()) {
            Assertions.assertTrue(sent.text().length() <= MAX_MESSAGE_LENGTH, () -> "mensaje de " + sent.text().length() + " caracteres: " + sent.text());
        }
    }

    @Test
    void noKeyboardRowEverExceedsWhatTelegramAccepts() {
        enactFascistLaws(4);
        resolvePendingPower();

        for (RecordingBotMessageService.Sent sent : messages.sent()) {
            if (sent.keyboard() == null) continue;

            for (InlineKeyboardRow row : sent.keyboard().getKeyboard()) {
                Assertions.assertTrue(row.size() <= MAX_BUTTONS_PER_ROW, () -> "fila de " + row.size() + " botones en: " + sent.text());
            }
        }
    }

    /**
     * Quien no tiene alias sigue siendo una persona con nombre: en los botones y en los mensajes se
     * le llama por él, y su identidad en el motor es la sintética {@code tg:<id>}, que es lo que
     * evita que dos usuarios sin alias colisionen en el índice único.
     */
    @Test
    void aPlayerWithoutATelegramAliasIsStillNamedProperly() {
        String engineUsername = telegramUserService.getByTelegramId(NO_ALIAS).getUser().getUsername();
        String displayName = telegramUserService.getByTelegramId(NO_ALIAS).getUser().getName();

        Assertions.assertEquals(TelegramUserUtils.syntheticUsernameOf(NO_ALIAS), engineUsername);
        Assertions.assertTrue(displayName.contains("Sinalias"), () -> "tiene que quedarle un nombre visible: '" + displayName + "'");
        Assertions.assertFalse(displayName.contains("@"), () -> "y sin arroba, que no tiene: '" + displayName + "'");

        // Y se le nombra en los sitios donde se nombra a los demás
        Assertions.assertTrue(messages.sentTo(GROUP_CHAT).stream().anyMatch(sent -> sent.text().contains(displayName)), "hay que listarlo en el grupo como a los demás");
    }

    /** Ningún nombre puede quedar vacío: un botón sin texto ni siquiera se puede pulsar. */
    @Test
    void noPlayerIsEverRenderedAsABlank() {
        enactFascistLaws(2);

        RecordingBotMessageService.Sent ask = messages.lastTo(telegramIdOf(president()));

        for (String text : ask.buttonTexts()) {
            Assertions.assertFalse(text.isBlank(), "un botón de jugador sin texto no se puede pulsar");
        }
    }

    private void givenRegisteredUserWithoutAlias(long telegramId, String firstName) {
        telegramUserService.register(org.telegram.telegrambots.meta.api.objects.User.builder().id(telegramId).isBot(false).firstName(firstName).languageCode("es").build());

        logInAs(telegramId, null, "private");
    }

}
