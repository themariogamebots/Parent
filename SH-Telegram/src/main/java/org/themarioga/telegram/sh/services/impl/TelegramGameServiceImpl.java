package org.themarioga.telegram.sh.services.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.themarioga.commons.engine.exceptions.ApplicationException;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.commons.engine.models.User;
import org.themarioga.engine.sh.models.Game;
import org.themarioga.engine.sh.models.Player;
import org.themarioga.telegram.sh.dao.intf.TelegramGameDao;
import org.themarioga.telegram.sh.dao.intf.TelegramPlayerDao;
import org.themarioga.telegram.sh.dao.intf.TelegramRoomDao;
import org.themarioga.telegram.sh.models.TelegramGame;
import org.themarioga.telegram.sh.models.TelegramPlayer;
import org.themarioga.telegram.sh.models.TelegramRoom;
import org.themarioga.telegram.sh.services.intf.TelegramGameService;

import java.util.List;

@Service
public class TelegramGameServiceImpl implements TelegramGameService {

    private final TelegramGameDao telegramGameDao;
    private final TelegramPlayerDao telegramPlayerDao;
    private final TelegramRoomDao telegramRoomDao;

    @Autowired
    public TelegramGameServiceImpl(TelegramGameDao telegramGameDao, TelegramPlayerDao telegramPlayerDao, TelegramRoomDao telegramRoomDao) {
        this.telegramGameDao = telegramGameDao;
        this.telegramPlayerDao = telegramPlayerDao;
        this.telegramRoomDao = telegramRoomDao;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public TelegramGame create(Game game, int firstMessageId, int creatorMessageId) {
        TelegramGame telegramGame = new TelegramGame();
        telegramGame.setGame(game);
        telegramGame.setFirstMessageId(firstMessageId);
        telegramGame.setCreatorMessageId(creatorMessageId);

        // create (persist), no createOrUpdate (merge): el id se deriva de la asociación con Game y
        // merge no puede insertar una entidad así ("Identifier may not be null")
        return telegramGameDao.create(telegramGame);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public TelegramGame getByGame(Game game) {
        return telegramGameDao.getByGame(game);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public TelegramGame getByCreator(User creator) {
        return telegramGameDao.getByCreator(creator);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public List<TelegramGame> getAll() {
        return telegramGameDao.getAll();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void setBoardMessageId(TelegramGame telegramGame, int messageId) {
        telegramGame.setBoardMessageId(messageId);

        telegramGameDao.createOrUpdate(telegramGame);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void setCurrentRoundMessageId(TelegramGame telegramGame, int messageId) {
        telegramGame.setCurrentRoundMessageId(messageId);

        telegramGameDao.createOrUpdate(telegramGame);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public Long getChatId(Room room) {
        TelegramRoom telegramRoom = telegramRoomDao.getByRoom(room);

        return telegramRoom != null ? telegramRoom.getId() : null;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public TelegramPlayer createPlayer(Player player, int roleMessageId) {
        TelegramPlayer telegramPlayer = new TelegramPlayer();
        telegramPlayer.setPlayer(player);
        telegramPlayer.setRoleMessageId(roleMessageId);

        return telegramPlayerDao.create(telegramPlayer);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public TelegramPlayer getByPlayer(Player player) {
        return telegramPlayerDao.getByPlayer(player);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public List<TelegramPlayer> getPlayers(Game game) {
        return telegramPlayerDao.getByGame(game);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void setActionMessageId(TelegramPlayer telegramPlayer, Integer messageId) {
        telegramPlayer.setActionMessageId(messageId);

        telegramPlayerDao.createOrUpdate(telegramPlayer);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deletePlayer(TelegramPlayer telegramPlayer) {
        telegramPlayerDao.delete(telegramPlayer);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteGameData(Game game) {
        for (TelegramPlayer telegramPlayer : telegramPlayerDao.getByGame(game)) {
            telegramPlayerDao.delete(telegramPlayer);
        }

        TelegramGame telegramGame = telegramGameDao.getByGame(game);
        if (telegramGame != null) telegramGameDao.delete(telegramGame);
    }

}
