package org.themarioga.telegram.sh.dao.impl;

import org.springframework.stereotype.Repository;
import org.themarioga.commons.engine.dao.AbstractHibernateDao;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.telegram.sh.dao.intf.TelegramRoomDao;
import org.themarioga.telegram.sh.models.TelegramRoom;

@Repository
public class TelegramRoomDaoImpl extends AbstractHibernateDao<TelegramRoom> implements TelegramRoomDao {

    public TelegramRoomDaoImpl() {
        setClazz(TelegramRoom.class);
    }

    @Override
    public TelegramRoom getByChatId(Long chatId) {
        return getCurrentSession().createQuery("SELECT tr FROM TelegramRoom tr JOIN FETCH tr.room WHERE tr.id = :chatId", TelegramRoom.class).setParameter("chatId", chatId).getSingleResultOrNull();
    }

    @Override
    public TelegramRoom getByRoom(Room room) {
        return getCurrentSession().createQuery("SELECT tr FROM TelegramRoom tr WHERE tr.room = :room", TelegramRoom.class).setParameter("room", room).getSingleResultOrNull();
    }

}
