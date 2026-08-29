package org.themarioga.telegram.sh.dao.intf;

import org.themarioga.commons.engine.dao.InterfaceHibernateDao;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.telegram.sh.models.TelegramRoom;

public interface TelegramRoomDao extends InterfaceHibernateDao<TelegramRoom> {

    TelegramRoom getByChatId(Long chatId);

    /**
     * Camino inverso, necesario para escribir al grupo desde un evento que llega por privado
     * (elegir canciller, descartar una ley, usar un poder).
     */
    TelegramRoom getByRoom(Room room);

}
