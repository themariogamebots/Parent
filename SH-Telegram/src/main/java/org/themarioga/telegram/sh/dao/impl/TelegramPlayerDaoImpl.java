package org.themarioga.telegram.sh.dao.impl;

import org.springframework.stereotype.Repository;
import org.themarioga.commons.engine.dao.AbstractHibernateDao;
import org.themarioga.engine.sh.models.Game;
import org.themarioga.engine.sh.models.Player;
import org.themarioga.telegram.sh.dao.intf.TelegramPlayerDao;
import org.themarioga.telegram.sh.models.TelegramPlayer;

import java.util.List;

@Repository
public class TelegramPlayerDaoImpl extends AbstractHibernateDao<TelegramPlayer> implements TelegramPlayerDao {

    public TelegramPlayerDaoImpl() {
        setClazz(TelegramPlayer.class);
    }

    @Override
    public TelegramPlayer getByPlayer(Player player) {
        return getCurrentSession().createQuery("SELECT tp FROM TelegramPlayer tp WHERE tp.player = :player", TelegramPlayer.class).setParameter("player", player).getSingleResultOrNull();
    }

    @Override
    public List<TelegramPlayer> getByGame(Game game) {
        return getCurrentSession().createQuery("SELECT tp FROM TelegramPlayer tp JOIN FETCH tp.player p WHERE p.game = :game", TelegramPlayer.class).setParameter("game", game).getResultList();
    }

}
