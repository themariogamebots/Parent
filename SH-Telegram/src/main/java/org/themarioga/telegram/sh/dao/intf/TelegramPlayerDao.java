package org.themarioga.telegram.sh.dao.intf;

import org.themarioga.commons.engine.dao.InterfaceHibernateDao;
import org.themarioga.engine.sh.models.Game;
import org.themarioga.engine.sh.models.Player;
import org.themarioga.telegram.sh.models.TelegramPlayer;

import java.util.List;

public interface TelegramPlayerDao extends InterfaceHibernateDao<TelegramPlayer> {

    TelegramPlayer getByPlayer(Player player);

    /**
     * Jugadores de una partida, para repartir los roles y para limpiar al terminar.
     */
    List<TelegramPlayer> getByGame(Game game);

}
