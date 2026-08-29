package org.themarioga.telegram.sh.services.intf;

import org.themarioga.commons.engine.models.Room;
import org.themarioga.commons.engine.models.User;
import org.themarioga.engine.sh.models.Game;
import org.themarioga.engine.sh.models.Player;
import org.themarioga.telegram.sh.models.TelegramGame;
import org.themarioga.telegram.sh.models.TelegramPlayer;

import java.util.List;

/**
 * Guarda la correspondencia entre lo que el motor entiende (partidas y jugadores) y lo que Telegram
 * necesita para pintarlo (identificadores de mensaje y de chat).
 * <p>
 * Aquí no hay reglas de juego: eso vive en {@code SHService}.
 */
public interface TelegramGameService {

    TelegramGame create(Game game, int firstMessageId, int creatorMessageId);

    TelegramGame getByGame(Game game);

    TelegramGame getByCreator(User creator);

    List<TelegramGame> getAll();

    void setBoardMessageId(TelegramGame telegramGame, int messageId);

    void setCurrentRoundMessageId(TelegramGame telegramGame, int messageId);

    /**
     * Id del chat de Telegram en el que se juega una partida. Es el camino que permite escribir al
     * grupo cuando la acción llega por el chat privado de un jugador, que en Secret Hitler es la
     * mayoría de ellas.
     */
    Long getChatId(Room room);

    TelegramPlayer createPlayer(Player player, int roleMessageId);

    TelegramPlayer getByPlayer(Player player);

    List<TelegramPlayer> getPlayers(Game game);

    void setActionMessageId(TelegramPlayer telegramPlayer, Integer messageId);

    void deletePlayer(TelegramPlayer telegramPlayer);

    /**
     * Borra las filas de Telegram de una partida terminada. No toca la partida en el motor.
     */
    void deleteGameData(Game game);

}
