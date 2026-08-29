package org.themarioga.telegram.sh.game.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.themarioga.commons.engine.enums.GameStatusEnum;
import org.themarioga.commons.engine.exceptions.ApplicationException;
import org.themarioga.commons.engine.exceptions.game.GameDoesntExistsException;
import org.themarioga.commons.engine.exceptions.game.GameOnlyCreatorCanPerformActionException;
import org.themarioga.commons.engine.exceptions.player.PlayerDoesntExistsException;
import org.themarioga.commons.engine.exceptions.user.UserAlreadyExistsException;
import org.themarioga.commons.engine.exceptions.user.UserDoesntExistsException;
import org.themarioga.commons.engine.models.Lang;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.commons.engine.models.User;
import org.themarioga.commons.engine.security.SecurityUtils;
import org.themarioga.commons.engine.services.intf.I18NService;
import org.themarioga.commons.engine.services.intf.RoomService;
import org.themarioga.commons.engine.services.intf.UserService;
import org.themarioga.commons.telegram.config.TelegramAdmins;
import org.themarioga.commons.telegram.models.TelegramUser;
import org.themarioga.commons.telegram.security.TelegramSecurityUtils;
import org.themarioga.commons.telegram.security.TelegramSession;
import org.themarioga.commons.telegram.services.intf.BotMessageService;
import org.themarioga.commons.telegram.services.intf.TelegramRoomResolver;
import org.themarioga.commons.telegram.services.intf.TelegramUserService;
import org.themarioga.commons.telegram.util.TelegramUserUtils;
import org.themarioga.engine.sh.config.GameConfig;
import org.themarioga.engine.sh.enums.GameResultEnum;
import org.themarioga.engine.sh.enums.LawTypeEnum;
import org.themarioga.engine.sh.enums.PartyEnum;
import org.themarioga.engine.sh.enums.RoleEnum;
import org.themarioga.engine.sh.enums.RoundActionsEnum;
import org.themarioga.engine.sh.enums.RoundStatusEnum;
import org.themarioga.engine.sh.enums.VoteEnum;
import org.themarioga.engine.sh.exceptions.law.LawNotFoundException;
import org.themarioga.engine.sh.models.Game;
import org.themarioga.engine.sh.models.Law;
import org.themarioga.engine.sh.models.Player;
import org.themarioga.engine.sh.models.Round;
import org.themarioga.engine.sh.service.intf.GameService;
import org.themarioga.engine.sh.service.intf.PlayerService;
import org.themarioga.engine.sh.service.intf.SHService;
import org.themarioga.telegram.sh.config.BotProperties;
import org.themarioga.telegram.sh.config.ErrorMessageResolver;
import org.themarioga.telegram.sh.game.service.intf.SHTelegramService;
import org.themarioga.telegram.sh.models.TelegramGame;
import org.themarioga.telegram.sh.models.TelegramPlayer;
import org.themarioga.telegram.sh.services.intf.TelegramGameService;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bot de juego de Secret Hitler.
 * <p>
 * Trabaja siempre sobre <b>dos chats a la vez</b>: el grupo, donde va el tablero y lo público, y el
 * privado de cada jugador, por donde va todo lo secreto (rol, leyes, poderes). Ninguno de esos
 * identificadores está en las entidades del motor: salen de {@code telegram_room},
 * {@code telegram_game} y {@code telegram_player}.
 * <p>
 * Estado: completo — lobby (S4), ronda entera (S5), poderes ejecutivos y veto (S6) y comandos de
 * administración (S7).
 */
@Service
@ConditionalOnProperty(prefix = "sh.bot", name = "enabled", havingValue = "true")
public class SHTelegramServiceImpl implements SHTelegramService {

    private static final Logger logger = LoggerFactory.getLogger(SHTelegramServiceImpl.class);

    /** Con 5 o 6 jugadores Hitler conoce a los fascistas; con 7 o más, no (regla real del juego). */
    private static final int MAX_PLAYERS_FOR_HITLER_TO_KNOW_FASCISTS = 6;

    /** Lo que admite {@code sendMessage} de Telegram. Solo la difusión puede acercarse: la escribe una persona. */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    private final BotMessageService botMessageService;
    private final SHService shService;
    private final GameService gameService;
    private final PlayerService playerService;
    private final UserService userService;
    private final TelegramUserService telegramUserService;
    private final TelegramGameService telegramGameService;
    private final TelegramRoomResolver roomResolver;
    private final I18NService i18NService;
    private final ErrorMessageResolver errorMessageResolver;
    private final GameConfig gameConfig;
    private final BotProperties botProperties;
    private final RoomService roomService;
    private final TelegramAdmins admins;

    /**
     * Interruptor de la difusión, en memoria y por proceso: es un freno de mano para cortar los
     * envíos masivos sin reiniciar, no una preferencia que haya que recordar entre arranques.
     */
    private boolean canSendGlobalMessages = true;

    @Autowired
    public SHTelegramServiceImpl(@Qualifier("shBotMessageService") BotMessageService botMessageService, SHService shService, GameService gameService, PlayerService playerService, UserService userService, TelegramUserService telegramUserService, TelegramGameService telegramGameService, TelegramRoomResolver roomResolver, I18NService i18NService, ErrorMessageResolver errorMessageResolver, GameConfig gameConfig, BotProperties botProperties, RoomService roomService, TelegramAdmins admins) {
        this.botMessageService = botMessageService;
        this.shService = shService;
        this.gameService = gameService;
        this.playerService = playerService;
        this.userService = userService;
        this.telegramUserService = telegramUserService;
        this.telegramGameService = telegramGameService;
        this.roomResolver = roomResolver;
        this.i18NService = i18NService;
        this.errorMessageResolver = errorMessageResolver;
        this.gameConfig = gameConfig;
        this.botProperties = botProperties;
        this.roomService = roomService;
        this.admins = admins;
    }

    // ///////////// Usuario //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void registerUser(org.telegram.telegrambots.meta.api.objects.User from) {
        try {
            telegramUserService.register(from);
        } catch (UserAlreadyExistsException e) {
            logger.warn("El usuario {} ya estaba registrado.", from.getId());
        }

        botMessageService.sendMessage(from.getId(), i18NService.get("PLAYER_WELCOME", from.getLanguageCode()));
    }

    @Override
    public void loginUser(long telegramId) {
        requireSession();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void changeUserLanguageMessage() {
        requireSession();

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
        for (Lang lang : i18NService.getLanguages()) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(lang.getName()).callbackData("change_user_lang__" + lang.getId()).build()));
        }

        botMessageService.sendMessage(chatId(), i18NService.get("USER_LANG_CHANGE"), keyboard.build());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void changeUserLanguage(int messageId, String lang) {
        User user = requireSession();

        userService.setLanguage(user, i18NService.getLanguage(lang));

        botMessageService.deleteMessage(chatId(), messageId);
        botMessageService.sendMessage(chatId(), i18NService.get("USER_LANG_CHANGED"));
    }

    // ///////////// Creación //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void startCreatingGame(long chatId, String chatTitle) {
        requireSession();

        long creatorChatId = chatId();
        String creating = i18NService.get("GAME_CREATING");

        // La partida no se crea hasta tener los identificadores de los tres mensajes, porque son los
        // que luego se editan según avanza. Se piden encadenados y sin bloquear el hilo que atiende
        // los updates; la sesión se lleva a la continuación, que corre en otro hilo.
        TelegramSession session = TelegramSession.capture();

        botMessageService.sendMessageAsync(chatId, creating).thenCompose(group -> botMessageService.sendMessageAsync(creatorChatId, creating).thenCompose(creatorMessage -> botMessageService.sendMessageAsync(creatorChatId, i18NService.get("PLAYER_JOINING")).thenAccept(playerMessage -> session.run(() -> createGame(chatId, chatTitle, group.getMessageId(), creatorMessage.getMessageId(), playerMessage.getMessageId()))))).exceptionally(e -> {
            logger.error("No se ha podido crear la partida en el chat {}: {}", chatId, e.getMessage(), e);

            return null;
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = ApplicationException.class)
    protected void createGame(long chatId, String chatTitle, int groupMessageId, int creatorMessageId, int playerMessageId) {
        try {
            Room room = roomResolver.resolveRoom(chatId, chatTitle);

            Game game = shService.createGame(room);

            TelegramGame telegramGame = telegramGameService.create(game, groupMessageId, creatorMessageId);
            telegramGameService.createPlayer(playerOf(game, requireSession()), playerMessageId);

            sendMainMenu(telegramGame);
            sendCreatorPrivateMenu(telegramGame);

            botMessageService.editMessage(chatId(), playerMessageId, i18NService.get("PLAYER_JOINED"));
        } catch (ApplicationException e) {
            logger.error("No se ha podido crear la partida en el chat {}: {}", chatId, e.getMessage());

            String message = errorMessageResolver.resolve(e);
            botMessageService.editMessage(chatId, groupMessageId, message);
            botMessageService.editMessage(chatId(), creatorMessageId, message);
        }
    }

    // ///////////// Configuración //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameMenuQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> sendMainMenu(getGameAndCheckCreator(chatId)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameConfigureQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> sendConfigMenu(getGameAndCheckCreator(chatId)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameSelectMaxPlayersQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);

            InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
            InlineKeyboardRow row = new InlineKeyboardRow();
            for (int number = gameConfig.getDefaultMinNumberOfPlayers(); number <= gameConfig.getDefaultMaxNumberOfPlayers(); number++) {
                row.add(InlineKeyboardButton.builder().text(String.valueOf(number)).callbackData("game_change_max_players__" + number).build());

                // De tres en tres, que es como caben en la pantalla de un móvil
                if (row.size() == 3) {
                    keyboard.keyboardRow(row);
                    row = new InlineKeyboardRow();
                }
            }
            if (!row.isEmpty()) keyboard.keyboardRow(row);
            keyboard.keyboardRow(new InlineKeyboardRow(button("GO_BACK", "game_configure")));

            editGroupMessage(telegramGame, getGameCreatedGroupMessage(telegramGame) + i18NService.get("GAME_SELECT_MAX_PLAYERS"), keyboard.build());
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameChangeMaxPlayers(long chatId, String callbackQueryId, String data) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);

            shService.setMaxNumberOfPlayers(telegramGame.getGame().getRoom(), Integer.parseInt(data));

            sendConfigMenu(telegramGame);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameSelectKickQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);
            Game game = telegramGame.getGame();

            InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
            for (Player player : game.getPlayers()) {
                // Al creador no se le puede echar: es el dueño de la partida y para eso está borrarla
                if (Objects.equals(player.getUser().getId(), game.getCreator().getId())) continue;

                keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(player.getUser().getName()).callbackData("game_kick__" + player.getUser().getId()).build()));
            }
            keyboard.keyboardRow(new InlineKeyboardRow(button("GO_BACK", "game_configure")));

            editGroupMessage(telegramGame, getGameCreatedGroupMessage(telegramGame) + "\n\n" + i18NService.get("SH_KICK_SELECT"), keyboard.build());
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameKickPlayer(long chatId, String callbackQueryId, String data) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);
            Game game = telegramGame.getGame();

            User kicked = userService.getById(UUID.fromString(data));
            if (kicked == null) throw new UserDoesntExistsException();

            // Los identificadores del privado del expulsado hay que recogerlos antes de que el motor
            // borre al jugador: después, la relación ya no se puede recorrer
            Player player = playerOf(game, kicked);
            TelegramPlayer telegramPlayer = telegramGameService.getByPlayer(player);
            Long kickedChatId = chatIdOf(kicked);
            Integer playerMessageId = telegramPlayer != null ? telegramPlayer.getRoleMessageId() : null;

            shService.kickPlayer(game.getRoom(), kicked);

            if (telegramPlayer != null) telegramGameService.deletePlayer(telegramPlayer);

            if (kickedChatId != null && playerMessageId != null) {
                botMessageService.editMessage(kickedChatId, playerMessageId, i18NService.get("SH_PLAYER_KICKED"));
            }

            sendMainMenu(telegramGame);
        });
    }

    // ///////////// Jugadores y arranque //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameJoinQuery(long chatId, String callbackQueryId) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByChatId(chatId);

            if (Objects.equals(telegramGame.getGame().getCreator().getId(), user.getId())) {
                botMessageService.answerCallbackQuery(callbackQueryId, i18NService.get("ERROR_PLAYER_ALREADY_JOINED"));
                return;
            }

            // El mensaje privado del jugador se envía antes de unirle, porque su identificador es lo
            // que hace falta para luego enseñarle ahí su rol.
            long playerChatId = chatId();
            TelegramSession session = TelegramSession.capture();

            botMessageService.sendMessageAsync(playerChatId, i18NService.get("PLAYER_JOINING")).thenAccept(joining -> session.run(() -> joinGame(chatId, joining.getMessageId()))).exceptionally(e -> {
                logger.error("No se ha podido unir al jugador {}: {}", playerChatId, e.getMessage(), e);

                return null;
            });
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = ApplicationException.class)
    protected void joinGame(long chatId, int playerMessageId) {
        guarded(() -> {
            TelegramGame telegramGame = getGameByChatId(chatId);

            Game game = shService.addPlayer(telegramGame.getGame().getRoom());

            telegramGameService.createPlayer(playerOf(game, requireSession()), playerMessageId);

            botMessageService.editMessage(chatId(), playerMessageId, i18NService.get("PLAYER_JOINED"), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("GAME_LEAVE", "game_leave"))).build());

            sendMainMenu(telegramGame);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void leaveGame(String callbackQueryId) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);

            // Hay que quedarse con el mensaje antes de que el motor borre al jugador
            TelegramPlayer telegramPlayer = telegramGameService.getByPlayer(playerOf(telegramGame.getGame(), user));
            Integer playerMessageId = telegramPlayer != null ? telegramPlayer.getRoleMessageId() : null;

            shService.leavePlayer(telegramGame.getGame().getRoom());

            if (telegramPlayer != null) telegramGameService.deletePlayer(telegramPlayer);

            botMessageService.answerCallbackQuery(callbackQueryId, i18NService.get("GAME_LEFT"));
            if (playerMessageId != null) botMessageService.deleteMessage(chatId(), playerMessageId);

            sendMainMenu(telegramGame);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameStartQuery(long chatId, String callbackQueryId) {
        requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameAndCheckCreator(chatId);

            // startGame reparte roles y arranca ya la primera ronda: el motor no deja la partida a
            // medias
            shService.startGame(telegramGame.getGame().getRoom());

            sendRoles(telegramGame);
            sendMainMenu(telegramGame);
            sendBoard(telegramGame);
            openNomination(telegramGame);
        });
    }

    // ///////////// Borrado //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameDeleteGroupQuery(long chatId, String callbackQueryId) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByChatId(chatId);
            Game game = telegramGame.getGame();

            if (Objects.equals(game.getCreator().getId(), user.getId())) {
                deleteGame(telegramGame);
                return;
            }

            // Quien no es el creador solo puede pedir el borrado, y solo con la partida en marcha
            if (game.getStatus() != GameStatusEnum.STARTED) {
                botMessageService.answerCallbackQuery(callbackQueryId, i18NService.get("ERROR_GAME_ONLY_CREATOR_CAN_DELETE"));
                return;
            }

            shService.voteForDeletion(game.getRoom());

            botMessageService.answerCallbackQuery(callbackQueryId, i18NService.get("PLAYER_VOTED_DELETION"));

            if (game.getStatus() == GameStatusEnum.DELETING) {
                deleteGame(telegramGame);
            } else {
                sendMainMenu(telegramGame);
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void gameDeletePrivateQuery(String callbackQueryId) {
        User user = requireSession();

        guarded(() -> deleteGame(getGameByCreator(user)));
    }

    // ///////////// Ayuda //////////////////

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void sendHelpMessage(long chatId) {
        BotProperties.Bot bot = botProperties.getGame();

        botMessageService.sendMessage(chatId, MessageFormat.format(i18NService.get("GAME_HELP"), bot.getDisplayName() + " (" + bot.getAlias() + ")", bot.getVersion(), bot.getHelpUrl(), bot.getOwnerAlias()));
    }

    // ///////////// Ronda //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void selectChancellorQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            Player chancellor = playerService.findById(UUID.fromString(data));
            if (chancellor == null) throw new PlayerDoesntExistsException();

            shService.setChancellorCandidate(game, chancellor);

            closeAction(playerOf(game, user), MessageFormat.format(i18NService.get("SH_CHANCELLOR_NOMINATED"), user.getName(), chancellor.getUser().getName()));

            openVoting(telegramGame);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void voteChancellorQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            shService.voteChancellor(game, playerOf(game, user), VoteEnum.valueOf(data));

            botMessageService.answerCallbackQuery(callbackQueryId, i18NService.get("SH_VOTE_CAST"));

            // El propio motor cierra la votación con el último voto que faltaba, así que lo que dice
            // qué toca ahora es el estado en el que ha quedado la ronda, no el número de votos
            if (game.getCurrentRound().getStatus() == RoundStatusEnum.VOTING_CHANCELLOR) {
                showVotingProgress(telegramGame);
            } else {
                revealVoting(telegramGame);
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void presidentDiscardLawQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            shService.presidentDiscardsLaw(game, lawOf(game, data));

            closeAction(playerOf(game, user), i18NService.get("SH_PRESIDENT_DISCARDED"));

            openChancellorEnact(telegramGame);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void chancellorEnactLawQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            Law enacted = lawOf(game, data);

            shService.chancellorSelectsLaw(game, enacted);

            closeAction(playerOf(game, user), i18NService.get("SH_CHANCELLOR_ENACTED"));

            announceLaw(telegramGame, enacted);
            afterLawEnacted(telegramGame);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void nextRoundQuery(long chatId, String callbackQueryId) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            // La regla del caos: la tercera elección fallida seguida promulga sola la ley de arriba
            // del mazo, y esa ley puede terminar la partida. Se detecta comparando el recuento,
            // porque el motor no avisa de que lo ha hecho.
            int liberalBefore = game.getNumberOfLiberalLawsEnacted();
            int fascistBefore = game.getNumberOfFascistLawsEnacted();

            shService.nextRound(game);

            if (game.getNumberOfLiberalLawsEnacted() > liberalBefore || game.getNumberOfFascistLawsEnacted() > fascistBefore) {
                String type = i18NService.get(game.getNumberOfFascistLawsEnacted() > fascistBefore ? "SH_LAW_FASCIST" : "SH_LAW_LIBERAL");

                sendToGroup(telegramGame, MessageFormat.format(i18NService.get("SH_LAW_AUTO_ENACTED"), type));
            }

            sendBoard(telegramGame);

            if (game.getStatus() == GameStatusEnum.ENDING) {
                endGame(telegramGame);
            } else {
                openNomination(telegramGame);
            }
        });
    }

    // ///////////// Flujo de la ronda //////////////////

    /**
     * Abre la ronda: el grupo ve quién preside y el presidente recibe por privado la lista de
     * candidatos a canciller. La lista la calcula el motor (vivos, ni él mismo, ni el gobierno
     * anterior salvo con cinco vivos o menos).
     */
    private void openNomination(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();
        Round round = game.getCurrentRound();

        if (round == null || round.getStatus() != RoundStatusEnum.SELECTING_CHANCELLOR) {
            logger.error("La partida {} no está eligiendo canciller", game.getId());
            return;
        }

        Player president = round.getPresident();

        startRoundMessage(telegramGame, MessageFormat.format(i18NService.get("SH_ROUND_STARTED"), round.getRoundNumber() + 1, president.getUser().getName()) + "\n\n" + MessageFormat.format(i18NService.get("SH_PRESIDENT_SELECTING_CHANCELLOR"), president.getUser().getName()), null);

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
        for (Player candidate : shService.getChancellorCandidates(game)) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(candidate.getUser().getName()).callbackData("sh_chancellor__" + candidate.getId()).build()));
        }

        sendAction(president, i18NService.get("SH_PRESIDENT_SELECT_CHANCELLOR"), keyboard.build());
    }

    /**
     * La votación va en el grupo: en Secret Hitler el sentido del voto es público, lo que no se sabe
     * hasta el final es el recuento.
     */
    private void openVoting(TelegramGame telegramGame) {
        Round round = telegramGame.getGame().getCurrentRound();

        editRoundMessage(telegramGame, MessageFormat.format(i18NService.get("SH_CHANCELLOR_NOMINATED"), round.getPresident().getUser().getName(), round.getChancellor().getUser().getName()), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(i18NService.get("SH_VOTE_JA")).callbackData("sh_vote__YES").build(), InlineKeyboardButton.builder().text(i18NService.get("SH_VOTE_NEIN")).callbackData("sh_vote__NO").build())).build());
    }

    /**
     * Mientras la votación sigue abierta solo se dice cuántos han votado, nunca qué han votado: el
     * recuento parcial revelaría el sentido del último voto.
     */
    private void showVotingProgress(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();
        Round round = game.getCurrentRound();

        editRoundMessage(telegramGame, MessageFormat.format(i18NService.get("SH_CHANCELLOR_NOMINATED"), round.getPresident().getUser().getName(), round.getChancellor().getUser().getName()) + "\n\n" + MessageFormat.format(i18NService.get("SH_VOTE_PENDING"), round.getChancellorVotes().size(), alivePlayers(game)), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(i18NService.get("SH_VOTE_JA")).callbackData("sh_vote__YES").build(), InlineKeyboardButton.builder().text(i18NService.get("SH_VOTE_NEIN")).callbackData("sh_vote__NO").build())).build());
    }

    /**
     * Cerrada la votación se revela el recuento y se ramifica según en qué estado haya dejado el
     * motor la ronda: gobierno rechazado, Hitler canciller (fin de partida) o sesión legislativa.
     */
    private void revealVoting(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();
        Round round = game.getCurrentRound();

        int yes = (int) round.getChancellorVotes().values().stream().filter(vote -> vote == VoteEnum.YES).count();
        int no = round.getChancellorVotes().size() - yes;

        StringBuilder message = new StringBuilder(MessageFormat.format(i18NService.get(round.getStatus() == RoundStatusEnum.CHANCELLOR_REJECTED ? "SH_VOTE_RESULT_REJECTED" : "SH_VOTE_RESULT_PASSED"), yes, no));

        for (Map.Entry<Player, VoteEnum> vote : round.getChancellorVotes().entrySet()) {
            message.append("\n").append(MessageFormat.format(i18NService.get("SH_VOTE_DETAIL"), vote.getKey().getUser().getName(), i18NService.get(vote.getValue() == VoteEnum.YES ? "SH_VOTE_JA" : "SH_VOTE_NEIN")));
        }

        switch (round.getStatus()) {
            case CHANCELLOR_REJECTED -> {
                // +1 porque el contador del motor no avanza al rechazar el gobierno, sino en el
                // nextRound siguiente; al jugador hay que enseñarle ya cómo queda
                message.append("\n\n").append(MessageFormat.format(i18NService.get("SH_ELECTION_TRACKER"), game.getFailedVotationCounter() + 1));

                editRoundMessage(telegramGame, message.toString(), nextRoundKeyboard());
            }
            case HITLER_ELECTED_CHANCELLOR -> {
                editRoundMessage(telegramGame, message.toString(), null);

                endGame(telegramGame);
            }
            case PRESIDENT_DISCARDING_LAW -> {
                editRoundMessage(telegramGame, message.append("\n\n").append(MessageFormat.format(i18NService.get("SH_PRESIDENT_DISCARDING"), round.getPresident().getUser().getName())).toString(), null);

                openPresidentDiscard(telegramGame);
            }
            default -> logger.error("La ronda de la partida {} ha quedado en un estado inesperado tras votar: {}", game.getId(), round.getStatus());
        }
    }

    /** Las tres leyes van al privado del presidente, nunca al grupo. */
    private void openPresidentDiscard(TelegramGame telegramGame) {
        Round round = telegramGame.getGame().getCurrentRound();

        sendAction(round.getPresident(), i18NService.get("SH_PRESIDENT_DISCARD"), lawKeyboard(round, "sh_discard__", false));
    }

    /** Y las dos que quedan, al privado del canciller. */
    private void openChancellorEnact(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();
        Round round = game.getCurrentRound();

        editRoundMessage(telegramGame, MessageFormat.format(i18NService.get("SH_CHANCELLOR_ENACTING"), round.getChancellor().getUser().getName()), null);

        sendAction(round.getChancellor(), i18NService.get("SH_CHANCELLOR_ENACT"), lawKeyboard(round, "sh_enact__", Boolean.TRUE.equals(game.getVetoIsActive())));
    }

    private void announceLaw(TelegramGame telegramGame, Law enacted) {
        sendToGroup(telegramGame, MessageFormat.format(i18NService.get("SH_LAW_ENACTED"), i18NService.get(enacted.getType() == LawTypeEnum.FASCIST ? "SH_LAW_FASCIST" : "SH_LAW_LIBERAL")));
    }

    /**
     * Después de promulgar, el motor deja la ronda en uno de tres sitios: fin de partida, poder
     * ejecutivo desbloqueado o turno de debate.
     */
    private void afterLawEnacted(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();

        sendBoard(telegramGame);

        switch (game.getCurrentRound().getStatus()) {
            case ENDING -> endGame(telegramGame);
            case ARGUING -> openArguing(telegramGame);
            case DOING_ADDITIONAL_ACTION -> openAdditionalAction(telegramGame);
            default -> logger.error("La ronda de la partida {} ha quedado en un estado inesperado tras promulgar: {}", game.getId(), game.getCurrentRound().getStatus());
        }
    }

    private void openArguing(TelegramGame telegramGame) {
        editRoundMessage(telegramGame, i18NService.get("SH_ARGUING"), nextRoundKeyboard());
    }

    /**
     * Fin de partida, en este orden: primero se lee todo lo que hace falta y se recogen los
     * identificadores de mensaje, después se anuncia, y solo al final se borra. Al revés no habría a
     * quién escribir, porque una vez borrada la partida las relaciones ya no se pueden recorrer.
     */
    private void endGame(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();

        GameResultEnum result = shService.getResult(game);
        List<PlayerMessage> playerMessages = collectPlayerMessages(game);
        String roles = getRolesRevealedMessage(game);

        String announcement = switch (result) {
            case LIBERAL_LAWS -> MessageFormat.format(i18NService.get("SH_WIN_LIBERAL_LAWS"), gameConfig.getLiberalLawsToWin());
            case FASCIST_LAWS -> MessageFormat.format(i18NService.get("SH_WIN_FASCIST_LAWS"), gameConfig.getFascistLawsToWin());
            case HITLER_EXECUTED -> i18NService.get("SH_WIN_HITLER_EXECUTED");
            case HITLER_ELECTED_CHANCELLOR -> i18NService.get("SH_WIN_HITLER_CHANCELLOR");
        };

        sendToGroup(telegramGame, announcement + "\n\n" + roles);

        for (PlayerMessage playerMessage : playerMessages) {
            botMessageService.deleteMessage(playerMessage.chatId(), playerMessage.messageId());
        }

        telegramGameService.deleteGameData(game);
        gameService.endGame(game);
    }

    private String getRolesRevealedMessage(Game game) {
        StringBuilder roles = new StringBuilder();

        for (Player player : game.getPlayers()) {
            String role = switch (player.getRole()) {
                case LIBERAL -> i18NService.get("SH_ROLE_NAME_LIBERAL");
                case FASCIST -> i18NService.get("SH_ROLE_NAME_FASCIST");
                case HITLER -> i18NService.get("SH_ROLE_NAME_HITLER");
            };

            roles.append("\n").append(MessageFormat.format(i18NService.get("SH_PLAYER_ROLE_LINE"), player.getUser().getName(), role));

            if (!player.isAlive()) roles.append(" ").append(i18NService.get("SH_PLAYER_DEAD_MARK"));
        }

        return MessageFormat.format(i18NService.get("SH_GAME_ROLES_REVEALED"), roles.toString());
    }

    // ///////////// Mensajes de la ronda //////////////////

    private InlineKeyboardMarkup nextRoundKeyboard() {
        return InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("SH_NEXT_ROUND_BUTTON", "sh_next_round"))).build();
    }

    /**
     * Las leyes que tiene delante quien decide, una por botón. El botón de vetar solo se le ofrece al
     * canciller, y solo cuando el veto ya está desbloqueado y no se ha propuesto ya en esta ronda.
     */
    private InlineKeyboardMarkup lawKeyboard(Round round, String callbackPrefix, boolean withVeto) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();

        for (Law law : round.getRoundAvailableLaws()) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(i18NService.get(law.getType() == LawTypeEnum.FASCIST ? "SH_LAW_FASCIST" : "SH_LAW_LIBERAL")).callbackData(callbackPrefix + law.getId()).build()));
        }

        if (withVeto) keyboard.keyboardRow(new InlineKeyboardRow(button("SH_VETO_BUTTON", "sh_veto")));

        return keyboard.build();
    }

    /** Un jugador por botón, para los poderes que eligen a alguien. */
    private InlineKeyboardMarkup playerKeyboard(List<Player> players, String callbackPrefix) {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();

        for (Player player : players) {
            keyboard.keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder().text(player.getUser().getName()).callbackData(callbackPrefix + player.getId()).build()));
        }

        return keyboard.build();
    }

    /**
     * Cada ronda estrena su mensaje en el grupo; dentro de la ronda se va editando ese mismo. Así el
     * tablero, que es otro mensaje, no se queda enterrado bajo la conversación.
     */
    private void startRoundMessage(TelegramGame telegramGame, String text, InlineKeyboardMarkup keyboard) {
        Long groupChatId = telegramGameService.getChatId(telegramGame.getGame().getRoom());
        if (groupChatId == null) return;

        TelegramSession session = TelegramSession.capture();

        botMessageService.sendMessageAsync(groupChatId, text).thenAccept(sent -> session.run(() -> telegramGameService.setCurrentRoundMessageId(telegramGame, sent.getMessageId()))).exceptionally(e -> {
            logger.error("No se ha podido enviar el mensaje de la ronda: {}", e.getMessage(), e);

            return null;
        });
    }

    private void editRoundMessage(TelegramGame telegramGame, String text, InlineKeyboardMarkup keyboard) {
        Long groupChatId = telegramGameService.getChatId(telegramGame.getGame().getRoom());
        if (groupChatId == null || telegramGame.getCurrentRoundMessageId() == null) return;

        if (keyboard == null) {
            botMessageService.editMessage(groupChatId, telegramGame.getCurrentRoundMessageId(), text);
        } else {
            botMessageService.editMessage(groupChatId, telegramGame.getCurrentRoundMessageId(), text, keyboard);
        }
    }

    private void sendToGroup(TelegramGame telegramGame, String text) {
        Long groupChatId = telegramGameService.getChatId(telegramGame.getGame().getRoom());
        if (groupChatId == null) return;

        botMessageService.sendMessage(groupChatId, text);
    }

    /**
     * Pide a un jugador la acción que le toca, por privado. El identificador del mensaje se guarda
     * para poder cerrarlo después: si no, los botones se quedarían ahí y el jugador podría volver a
     * pulsarlos (el motor lo rechazaría, pero es confuso).
     */
    private void sendAction(Player player, String text, InlineKeyboardMarkup keyboard) {
        TelegramPlayer telegramPlayer = telegramGameService.getByPlayer(player);
        Long playerChatId = chatIdOf(player.getUser());
        if (telegramPlayer == null || playerChatId == null) return;

        TelegramSession session = TelegramSession.capture();

        botMessageService.sendMessageAsync(playerChatId, text).thenAccept(sent -> session.run(() -> {
            telegramGameService.setActionMessageId(telegramPlayer, sent.getMessageId());

            botMessageService.editMessage(playerChatId, sent.getMessageId(), text, keyboard);
        })).exceptionally(e -> {
            logger.error("No se ha podido pedir la acción al jugador {}: {}", playerChatId, e.getMessage(), e);

            return null;
        });
    }

    private void closeAction(Player player, String text) {
        TelegramPlayer telegramPlayer = telegramGameService.getByPlayer(player);
        Long playerChatId = chatIdOf(player.getUser());
        if (telegramPlayer == null || playerChatId == null || telegramPlayer.getActionMessageId() == null) return;

        botMessageService.editMessage(playerChatId, telegramPlayer.getActionMessageId(), text);

        telegramGameService.setActionMessageId(telegramPlayer, null);
    }

    private Law lawOf(Game game, String data) {
        long lawId = Long.parseLong(data);

        return game.getCurrentRound().getRoundAvailableLaws().stream().filter(law -> law.getId() == lawId).findFirst().orElseThrow(LawNotFoundException::new);
    }

    private long alivePlayers(Game game) {
        return game.getPlayers().stream().filter(Player::isAlive).count();
    }

    // ///////////// Poderes y veto //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void proposeVetoQuery(String callbackQueryId) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            Player chancellor = game.getCurrentRound().getChancellor();
            Player president = game.getCurrentRound().getPresident();

            shService.proposeVeto(game);

            closeAction(chancellor, i18NService.get("SH_VETO_PROPOSED"));

            editRoundMessage(telegramGame, i18NService.get("SH_VETO_PROPOSED"), null);

            sendAction(president, i18NService.get("SH_VETO_ASK_PRESIDENT"), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("YES", "sh_veto_resolve__YES"), button("NO", "sh_veto_resolve__NO"))).build());
        });
    }

    /**
     * El presidente decide sobre el veto. Aceptarlo tira las dos leyes y cuenta como gobierno
     * fallido —el motor deja la ronda en {@code CHANCELLOR_REJECTED}, igual que un voto perdido—;
     * rechazarlo devuelve la pelota al canciller, que ya no puede volver a vetar esta ronda.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void resolveVetoQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            boolean accepted = VoteEnum.valueOf(data) == VoteEnum.YES;

            Round round = game.getCurrentRound();
            Player president = round.getPresident();
            Player chancellor = round.getChancellor();

            shService.resolveVeto(game, accepted);

            closeAction(president, i18NService.get(accepted ? "SH_VETO_ACCEPTED" : "SH_VETO_REJECTED"));

            if (accepted) {
                // +1 por lo mismo que al rechazar el gobierno: el motor no mueve el contador hasta
                // el nextRound siguiente, y al jugador hay que enseñarle ya cómo queda
                editRoundMessage(telegramGame, i18NService.get("SH_VETO_ACCEPTED") + "\n\n" + MessageFormat.format(i18NService.get("SH_ELECTION_TRACKER"), game.getFailedVotationCounter() + 1), nextRoundKeyboard());
            } else {
                editRoundMessage(telegramGame, i18NService.get("SH_VETO_REJECTED"), null);

                sendAction(chancellor, i18NService.get("SH_CHANCELLOR_ENACT"), lawKeyboard(game.getCurrentRound(), "sh_enact__", false));
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void selectActionQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);

            RoundActionsEnum action = RoundActionsEnum.valueOf(data);

            if (action == RoundActionsEnum.POLICY_PEEK) {
                peekTopLaws(telegramGame);
            } else {
                openAction(telegramGame, action);
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void investigatePlayerQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            Player target = requirePlayer(data);

            // Los nombres se leen antes de llamar al motor: investigar vuelve a mezclar la partida
            // en la sesión y las entidades de después ya no son necesariamente las mismas
            Player president = game.getCurrentRound().getPresident();
            String presidentName = president.getUser().getName();
            String targetName = target.getUser().getName();

            PartyEnum party = shService.investigatePlayer(game, target);

            // El partido que sale de investigar es lo más secreto del juego: solo al privado del
            // presidente. En el grupo se dice a quién ha investigado, nunca el resultado
            closeAction(president, MessageFormat.format(i18NService.get("SH_POWER_INVESTIGATE_RESULT"), targetName, i18NService.get(party == PartyEnum.FASCIST ? "SH_PARTY_FASCIST" : "SH_PARTY_LIBERAL")));

            sendToGroup(telegramGame, MessageFormat.format(i18NService.get("SH_POWER_INVESTIGATE_ANNOUNCE"), presidentName, targetName));

            openArguing(telegramGame);
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void callSpecialElectionQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            Player target = requirePlayer(data);

            Player president = game.getCurrentRound().getPresident();
            String announcement = MessageFormat.format(i18NService.get("SH_POWER_SPECIAL_ELECTION_ANNOUNCE"), president.getUser().getName(), target.getUser().getName());

            shService.callSpecialElection(game, target);

            closeAction(president, announcement);

            sendToGroup(telegramGame, announcement);

            openArguing(telegramGame);
        });
    }

    /**
     * La ejecución es el único poder que puede terminar la partida: si el ejecutado era Hitler,
     * ganan los liberales ahí mismo.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void killPlayerQuery(String callbackQueryId, String data) {
        User user = requireSession();

        guarded(() -> {
            TelegramGame telegramGame = getGameByPlayer(user);
            Game game = telegramGame.getGame();

            Player target = requirePlayer(data);

            Player president = game.getCurrentRound().getPresident();
            String announcement = MessageFormat.format(i18NService.get("SH_POWER_EXECUTION_ANNOUNCE"), president.getUser().getName(), target.getUser().getName());

            shService.killPlayer(game, target);

            closeAction(president, announcement);

            sendToGroup(telegramGame, announcement);

            // El tablero cuenta los vivos, así que hay que repintarlo aunque no cambie ninguna ley
            sendBoard(telegramGame);

            if (game.getCurrentRound().getStatus() == RoundStatusEnum.ENDING) {
                endGame(telegramGame);
            } else {
                openArguing(telegramGame);
            }
        });
    }

    /**
     * Abre el poder ejecutivo que ha desbloqueado la ley fascista recién promulgada.
     * <p>
     * {@code ENABLE_VETO} se cae de la lista antes de nada: no es una elección del presidente, el
     * motor lo activa solo al promulgar la quinta ley fascista y lo único que queda por hacer es
     * contarlo en el grupo. Lo que sí puede pasar es que aparezca junto a {@code EXECUTION}, y
     * entonces el poder que hay que ofrecer sigue siendo uno solo.
     */
    private void openAdditionalAction(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();
        Player president = game.getCurrentRound().getPresident();

        List<RoundActionsEnum> actions = new ArrayList<>(shService.getAvailableActions(game));

        if (actions.remove(RoundActionsEnum.ENABLE_VETO)) {
            sendToGroup(telegramGame, MessageFormat.format(i18NService.get("SH_VETO_UNLOCKED"), game.getNumberOfFascistLawsEnacted()));
        }

        if (actions.isEmpty()) {
            logger.error("La partida {} está esperando un poder ejecutivo y no hay ninguno disponible", game.getId());
            return;
        }

        editRoundMessage(telegramGame, MessageFormat.format(i18NService.get("SH_PRESIDENT_USING_POWER"), president.getUser().getName()), null);

        if (actions.size() == 1) {
            openAction(telegramGame, actions.get(0));
        } else {
            InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
            for (RoundActionsEnum action : actions) {
                keyboard.keyboardRow(new InlineKeyboardRow(button(actionButtonTag(action), "sh_action__" + action.name())));
            }

            sendAction(president, i18NService.get("SH_POWER_SELECT"), keyboard.build());
        }
    }

    /**
     * Pide al presidente, por privado, lo que le falta para usar el poder.
     * <p>
     * Espiar el mazo no elige a nadie, pero aun así necesita un botón: el motor comprueba quién
     * actúa contra el usuario de la sesión (D6), y la sesión de quien acaba de promulgar es la del
     * canciller, no la del presidente. Sin ese botón el bot no tendría forma de ejecutarlo.
     */
    private void openAction(TelegramGame telegramGame, RoundActionsEnum action) {
        Game game = telegramGame.getGame();
        Player president = game.getCurrentRound().getPresident();

        switch (action) {
            case INVESTIGATE_LOYALTY ->
                    sendAction(president, i18NService.get("SH_POWER_INVESTIGATE_SELECT"), playerKeyboard(shService.getInspectablePlayers(game), "sh_investigate__"));
            case SPECIAL_ELECTION ->
                    sendAction(president, i18NService.get("SH_POWER_SPECIAL_ELECTION_SELECT"), playerKeyboard(specialElectionCandidates(game), "sh_special_election__"));
            case EXECUTION ->
                    sendAction(president, i18NService.get("SH_POWER_EXECUTION_SELECT"), playerKeyboard(shService.getKillablePlayers(game), "sh_kill__"));
            case POLICY_PEEK ->
                    sendAction(president, i18NService.get("SH_POWER_PEEK_CONFIRM"), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("SH_POWER_PEEK_BUTTON", "sh_action__" + RoundActionsEnum.POLICY_PEEK.name()))).build());
            default -> logger.error("Poder ejecutivo no soportado en la partida {}: {}", game.getId(), action);
        }
    }

    /** Las tres leyes de arriba del mazo, al privado del presidente y a ningún sitio más. */
    private void peekTopLaws(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();

        Player president = game.getCurrentRound().getPresident();
        String presidentName = president.getUser().getName();

        List<Law> topLaws = shService.peekTopLaws(game);

        closeAction(president, MessageFormat.format(i18NService.get("SH_POWER_PEEK_RESULT"), namesOfLaws(topLaws)));

        sendToGroup(telegramGame, MessageFormat.format(i18NService.get("SH_POWER_PEEK_ANNOUNCE"), presidentName));

        openArguing(telegramGame);
    }

    /**
     * A quién puede nombrar presidente una elección especial: cualquier vivo que no sea el
     * presidente actual. El motor lo vuelve a comprobar; esto es solo para no ofrecer el botón.
     */
    private List<Player> specialElectionCandidates(Game game) {
        Player president = game.getCurrentRound().getPresident();

        return game.getPlayers().stream().filter(Player::isAlive).filter(player -> !player.equals(president)).toList();
    }

    private String actionButtonTag(RoundActionsEnum action) {
        return switch (action) {
            case INVESTIGATE_LOYALTY -> "SH_POWER_INVESTIGATE_BUTTON";
            case SPECIAL_ELECTION -> "SH_POWER_SPECIAL_ELECTION_BUTTON";
            case POLICY_PEEK -> "SH_POWER_PEEK_BUTTON";
            case EXECUTION -> "SH_POWER_EXECUTION_BUTTON";
            case NONE, ENABLE_VETO -> "UNKNOWN_ERROR";
        };
    }

    private String namesOfLaws(List<Law> laws) {
        return laws.stream().map(law -> i18NService.get(law.getType() == LawTypeEnum.FASCIST ? "SH_LAW_FASCIST" : "SH_LAW_LIBERAL")).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private Player requirePlayer(String data) {
        Player player = playerService.findById(UUID.fromString(data));
        if (player == null) throw new PlayerDoesntExistsException();

        return player;
    }

    // ///////////// Administración //////////////////

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteMyGames() {
        User user = requireSession();

        guarded(() -> deleteGame(getGameByCreator(user)));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteGameByCreatorUsername(String username) {
        guarded(() -> {
            requireAdmin();

            // El alias se resuelve contra la identidad del motor, normalizando lo que teclee quien
            // administra (con o sin arroba, en cualquier combinación de mayúsculas)
            User creator = userService.getByUsername(TelegramUserUtils.normalizeUsername(username));
            if (creator == null) throw new UserDoesntExistsException();

            deleteGame(getGameByCreator(creator));

            notifyAdmins(MessageFormat.format(i18NService.get("GAME_DELETION_USER"), username));
        });
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = ApplicationException.class)
    public void deleteAllGames() {
        guarded(() -> {
            requireAdmin();

            for (TelegramGame telegramGame : telegramGameService.getAll()) {
                // Que una partida falle no puede impedir que se borren las demás: son partidas de
                // gente distinta y quien administra ha pedido borrarlas todas, no las que salgan bien
                try {
                    Long creatorChatId = chatIdOf(telegramGame.getGame().getCreator());

                    deleteGame(telegramGame);

                    if (creatorChatId != null) {
                        botMessageService.sendMessage(creatorChatId, i18NService.get("GAME_DELETION_FORCED"));
                    }
                } catch (ApplicationException e) {
                    logger.error("No se ha podido borrar la partida {}: {}", telegramGame.getGame().getId(), e.getMessage(), e);
                }
            }

            notifyAdmins(i18NService.get("GAME_DELETION_ALL"));
        });
    }

    /**
     * Difusión a todo el mundo: a cada usuario por su privado y a cada sala por su grupo.
     * <p>
     * El texto se mide <b>antes</b> de empezar, y no por pulcritud: un envío que Telegram rechaza
     * marca el chat como inactivo, así que un mensaje vacío o más largo de la cuenta daría de baja
     * a toda la base de datos de una tacada.
     */
    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void sendMessageToEveryone(String message) {
        guarded(() -> {
            requireAdmin();

            if (!canSendGlobalMessages) {
                logger.info("Los mensajes globales están desactivados");

                notifyAdmins(i18NService.get("GAME_GLOBAL_MESSAGES_OFF"));

                return;
            }

            if (message == null || message.isBlank() || message.length() > MAX_MESSAGE_LENGTH) {
                logger.warn("Difusión rechazada: el mensaje mide {} caracteres", message == null ? 0 : message.length());

                notifyAdmins(i18NService.get("ERROR_MESSAGE_TOO_LONG"));

                return;
            }

            for (User user : userService.getAllUsers()) {
                Long userChatId = chatIdOf(user);
                if (userChatId == null) continue;

                sendGlobalMessage(userChatId, message, active -> userService.setActive(user, active));
            }

            for (Room room : roomService.getAllRooms()) {
                Long roomChatId = telegramGameService.getChatId(room);
                if (roomChatId == null) continue;

                sendGlobalMessage(roomChatId, message, active -> roomService.setActive(room, active));
            }

            notifyAdmins(i18NService.get("ALL_MESSAGES_SENT"));
        });
    }

    /**
     * Envía y, según cómo acabe, marca al destinatario como activo o inactivo: si Telegram rechaza
     * el envío es que el usuario bloqueó al bot o que el bot ya no está en el grupo.
     */
    private void sendGlobalMessage(long chatId, String message, Consumer<Boolean> setActive) {
        TelegramSession session = TelegramSession.capture();

        botMessageService.sendMessageAsync(chatId, message).thenAccept(sent -> session.run(() -> setActive.accept(true))).exceptionally(e -> {
            logger.warn("Desactivando el chat {} tras fallar el envío: {}", chatId, e.getMessage());

            session.run(() -> setActive.accept(false));

            return null;
        });
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public void toggleGlobalMessages() {
        guarded(() -> {
            requireAdmin();

            canSendGlobalMessages = !canSendGlobalMessages;

            notifyAdmins(i18NService.get(canSendGlobalMessages ? "GAME_GLOBAL_MESSAGES_ON" : "GAME_GLOBAL_MESSAGES_OFF"));
        });
    }

    private void notifyAdmins(String message) {
        for (Long adminChatId : admins.getIds()) {
            botMessageService.sendMessage(adminChatId, message);
        }
    }

    /**
     * El rol sale de la sesión, que lo pone el interceptor a partir de {@code telegram.bots.admin-ids}.
     * Se reutiliza el error de "esto solo lo hace el creador" porque dice justo lo que hay que
     * decirle a quien lo intenta sin serlo, y no hace falta un tag más.
     * <p>
     * Va <b>dentro</b> del {@code guarded}, no delante: si no, la excepción se escaparía a la capa
     * de arriba, que solo la apunta en el log, y quien ha tecleado el comando no vería nada.
     */
    private void requireAdmin() {
        requireSession();

        if (!SecurityUtils.isAdmin()) throw new GameOnlyCreatorCanPerformActionException();
    }

    // ///////////// Menús y tablero //////////////////

    private void sendMainMenu(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder keyboard = InlineKeyboardMarkup.builder();
        if (game.getStatus() == GameStatusEnum.CREATED) {
            if (game.getPlayers().size() < game.getMaxNumberOfPlayers()) {
                keyboard.keyboardRow(new InlineKeyboardRow(button("GAME_JOIN_BUTTON", "game_join")));
            }

            keyboard.keyboardRow(new InlineKeyboardRow(button("GAME_CONFIGURE_BUTTON", "game_configure")));

            if (game.getPlayers().size() >= gameConfig.getDefaultMinNumberOfPlayers()) {
                keyboard.keyboardRow(new InlineKeyboardRow(button("GAME_START_BUTTON", "game_start")));
            }
        }
        keyboard.keyboardRow(new InlineKeyboardRow(button("GAME_DELETE_BUTTON", "game_delete_group")));

        String message = getGameCreatedGroupMessage(telegramGame);
        if (game.getStatus() != GameStatusEnum.STARTED) {
            if (game.getPlayers().size() > 1) {
                message += "\n\n" + getCurrentPlayerNumberMessage(telegramGame);
            }
        } else if (!game.getDeletionVotes().isEmpty()) {
            message += "\n\n" + getCurrentVoteDeletionNumberMessage(telegramGame);
        }

        editGroupMessage(telegramGame, message, keyboard.build());
    }

    private void sendCreatorPrivateMenu(TelegramGame telegramGame) {
        Long creatorChatId = chatIdOf(telegramGame.getGame().getCreator());
        if (creatorChatId == null) return;

        botMessageService.editMessage(creatorChatId, telegramGame.getCreatorMessageId(), i18NService.get("PLAYER_CREATED_GAME"), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("GAME_DELETE_BUTTON", "game_delete_private"))).build());
    }

    private void sendConfigMenu(TelegramGame telegramGame) {
        editGroupMessage(telegramGame, getGameCreatedGroupMessage(telegramGame), InlineKeyboardMarkup.builder().keyboardRow(new InlineKeyboardRow(button("GAME_CHANGE_MAX_N_PLAYERS", "game_sel_max_players"))).keyboardRow(new InlineKeyboardRow(button("SH_KICK_BUTTON", "game_sel_kick"))).keyboardRow(new InlineKeyboardRow(button("GO_BACK", "game_menu"))).build());
    }

    /**
     * El reparto de roles, que es lo único de este bot que <b>no</b> puede equivocarse de chat: cada
     * rol va al privado de su jugador, editando el mensaje que se le envió al unirse.
     * <p>
     * Quién ve a quién es regla de juego que el motor no modela: los fascistas se conocen entre
     * ellos y conocen a Hitler siempre; Hitler solo conoce a los fascistas en partidas de 5 o 6
     * jugadores.
     */
    private void sendRoles(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();

        for (TelegramPlayer telegramPlayer : telegramGameService.getPlayers(game)) {
            Player player = telegramPlayer.getPlayer();

            Long playerChatId = chatIdOf(player.getUser());
            if (playerChatId == null) continue;

            botMessageService.editMessage(playerChatId, telegramPlayer.getRoleMessageId(), getRoleMessage(game, player));
        }
    }

    private String getRoleMessage(Game game, Player player) {
        StringBuilder message = new StringBuilder();

        switch (player.getRole()) {
            case LIBERAL -> message.append(MessageFormat.format(i18NService.get("SH_ROLE_LIBERAL"), gameConfig.getLiberalLawsToWin()));
            case FASCIST -> message.append(MessageFormat.format(i18NService.get("SH_ROLE_FASCIST"), gameConfig.getFascistLawsToWin()));
            case HITLER -> message.append(i18NService.get("SH_ROLE_HITLER"));
        }

        if (player.getRole() == RoleEnum.FASCIST) {
            String fellows = namesOf(game.getPlayers().stream().filter(other -> other.getRole() == RoleEnum.FASCIST).filter(other -> !other.equals(player)).toList());

            if (!fellows.isEmpty()) {
                message.append("\n").append(MessageFormat.format(i18NService.get("SH_ROLE_FELLOW_FASCISTS"), fellows));
            }

            message.append("\n").append(MessageFormat.format(i18NService.get("SH_ROLE_HITLER_IS"), namesOf(game.getPlayers().stream().filter(other -> other.getRole() == RoleEnum.HITLER).toList())));
        } else if (player.getRole() == RoleEnum.HITLER) {
            if (game.getPlayers().size() <= MAX_PLAYERS_FOR_HITLER_TO_KNOW_FASCISTS) {
                message.append("\n").append(MessageFormat.format(i18NService.get("SH_ROLE_FELLOW_FASCISTS"), namesOf(game.getPlayers().stream().filter(other -> other.getRole() == RoleEnum.FASCIST).toList())));
            } else {
                message.append("\n").append(i18NService.get("SH_ROLE_HITLER_UNKNOWN"));
            }
        }

        return message.toString();
    }

    /**
     * El tablero: lo público de la partida. Es un mensaje propio, aparte del menú y de la fase en
     * curso, porque es lo único que interesa tener a la vista toda la partida.
     */
    private void sendBoard(TelegramGame telegramGame) {
        Long groupChatId = telegramGameService.getChatId(telegramGame.getGame().getRoom());
        if (groupChatId == null) {
            logger.error("La sala {} no tiene chat de Telegram asociado", telegramGame.getGame().getRoom().getId());
            return;
        }

        String board = getBoardMessage(telegramGame.getGame());

        if (telegramGame.getBoardMessageId() != null) {
            botMessageService.editMessage(groupChatId, telegramGame.getBoardMessageId(), board);
            return;
        }

        TelegramSession session = TelegramSession.capture();

        botMessageService.sendMessageAsync(groupChatId, board).thenAccept(sent -> session.run(() -> telegramGameService.setBoardMessageId(telegramGame, sent.getMessageId()))).exceptionally(e -> {
            logger.error("No se ha podido enviar el tablero: {}", e.getMessage(), e);

            return null;
        });
    }

    private String getBoardMessage(Game game) {
        StringBuilder message = new StringBuilder(MessageFormat.format(i18NService.get("SH_BOARD"), game.getNumberOfLiberalLawsEnacted(), gameConfig.getLiberalLawsToWin(), game.getNumberOfFascistLawsEnacted(), gameConfig.getFascistLawsToWin(), game.getFailedVotationCounter(), game.getPlayers().stream().filter(Player::isAlive).count()));

        if (Boolean.TRUE.equals(game.getVetoIsActive())) {
            message.append("\n").append(i18NService.get("SH_BOARD_VETO_ENABLED"));
        }

        return message.toString();
    }

    /**
     * Borra la partida y limpia los mensajes que dejó por los chats.
     * <p>
     * Los identificadores se recogen <b>antes</b> de que el motor borre la partida: después, las
     * relaciones ya no se pueden recorrer.
     */
    private void deleteGame(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();

        List<PlayerMessage> playerMessages = collectPlayerMessages(game);
        Long groupChatId = telegramGameService.getChatId(game.getRoom());
        Long creatorChatId = chatIdOf(game.getCreator());
        Integer firstMessageId = telegramGame.getFirstMessageId();
        Integer creatorMessageId = telegramGame.getCreatorMessageId();
        Integer boardMessageId = telegramGame.getBoardMessageId();
        Integer roundMessageId = telegramGame.getCurrentRoundMessageId();

        telegramGameService.deleteGameData(game);
        shService.deleteGameByCreator(game.getRoom());

        for (PlayerMessage playerMessage : playerMessages) {
            botMessageService.deleteMessage(playerMessage.chatId(), playerMessage.messageId());
        }
        if (groupChatId != null) {
            if (boardMessageId != null) botMessageService.deleteMessage(groupChatId, boardMessageId);
            if (roundMessageId != null) botMessageService.deleteMessage(groupChatId, roundMessageId);
        }

        String deleted = i18NService.get("GAME_DELETED");
        if (groupChatId != null) botMessageService.editMessage(groupChatId, firstMessageId, deleted);
        if (creatorChatId != null) botMessageService.editMessage(creatorChatId, creatorMessageId, deleted);
    }

    private record PlayerMessage(long chatId, int messageId) {
    }

    private List<PlayerMessage> collectPlayerMessages(Game game) {
        return telegramGameService.getPlayers(game).stream().map(telegramPlayer -> {
            Long playerChatId = chatIdOf(telegramPlayer.getPlayer().getUser());

            return playerChatId != null ? new PlayerMessage(playerChatId, telegramPlayer.getRoleMessageId()) : null;
        }).filter(Objects::nonNull).toList();
    }

    /**
     * Edita el mensaje de la partida en el grupo. El chat no está en la entidad del motor: hay que
     * resolverlo por la tabla de equivalencias.
     */
    private void editGroupMessage(TelegramGame telegramGame, String message, InlineKeyboardMarkup keyboard) {
        Long groupChatId = telegramGameService.getChatId(telegramGame.getGame().getRoom());
        if (groupChatId == null) {
            logger.error("La sala {} no tiene chat de Telegram asociado", telegramGame.getGame().getRoom().getId());
            return;
        }

        botMessageService.editMessage(groupChatId, telegramGame.getFirstMessageId(), message, keyboard);
    }

    private InlineKeyboardButton button(String textTag, String callbackData) {
        return InlineKeyboardButton.builder().text(i18NService.get(textTag)).callbackData(callbackData).build();
    }

    // ///////////// Sesión, partida y permisos //////////////////

    private long chatId() {
        Long telegramId = TelegramSecurityUtils.getTelegramId();
        if (telegramId == null) throw new UserDoesntExistsException();

        return telegramId;
    }

    private User requireSession() {
        User user = SecurityUtils.getUser();
        if (user == null) throw new UserDoesntExistsException();

        return user;
    }

    /**
     * Chat privado de un usuario, o {@code null} si no lo tiene (por ejemplo si viene de otra
     * plataforma).
     */
    private Long chatIdOf(User user) {
        TelegramUser telegramUser = telegramUserService.getByUser(user);
        if (telegramUser == null) {
            logger.warn("El usuario {} no tiene chat de Telegram asociado", user.getId());
            return null;
        }

        return telegramUser.getId();
    }

    private TelegramGame getGameByChatId(long chatId) {
        Room room = roomResolver.resolveRoom(chatId, null);

        Game game = gameService.getByRoom(room);
        if (game == null) throw new GameDoesntExistsException();

        TelegramGame telegramGame = telegramGameService.getByGame(game);
        if (telegramGame == null) throw new GameDoesntExistsException();

        return telegramGame;
    }

    private TelegramGame getGameByCreator(User creator) {
        TelegramGame telegramGame = telegramGameService.getByCreator(creator);
        if (telegramGame == null) throw new GameDoesntExistsException();

        return telegramGame;
    }

    private TelegramGame getGameByPlayer(User user) {
        Player player = playerService.findByUser(user);
        if (player == null) throw new PlayerDoesntExistsException();

        // player.getGame() devuelve la Game abstracta de commons, no la de SH (el modelo de SH no
        // redefine el getter con el tipo concreto), así que la partida se recupera por su sala
        Game game = gameService.getByRoom(player.getGame().getRoom());
        if (game == null) throw new GameDoesntExistsException();

        TelegramGame telegramGame = telegramGameService.getByGame(game);
        if (telegramGame == null) throw new GameDoesntExistsException();

        return telegramGame;
    }

    private Player playerOf(Game game, User user) {
        Player player = playerService.findPlayerByGameAndUser(game, user);
        if (player == null) throw new PlayerDoesntExistsException();

        return player;
    }

    private TelegramGame getGameAndCheckCreator(long chatId) {
        TelegramGame telegramGame = getGameByChatId(chatId);

        if (!Objects.equals(telegramGame.getGame().getCreator().getId(), requireSession().getId()))
            throw new GameOnlyCreatorCanPerformActionException();

        return telegramGame;
    }

    // ///////////// Errores //////////////////

    /**
     * Ejecuta la acción y, si el motor la rechaza, se lo cuenta al usuario.
     * <p>
     * Si el update venía de un botón se contesta a la propia pulsación, que es como avisa este bot
     * sin ensuciar el grupo con mensajes de error; si venía de un comando, con un mensaje normal.
     */
    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (ApplicationException e) {
            String message = errorMessageResolver.resolve(e);

            String callbackQueryId = TelegramSecurityUtils.getCallbackQueryId();
            if (callbackQueryId != null) {
                botMessageService.answerCallbackQuery(callbackQueryId, message);
            } else {
                botMessageService.sendMessage(chatId(), message);
            }
        }
    }

    // ///////////// Textos //////////////////

    private String getGameCreatedGroupMessage(TelegramGame telegramGame) {
        Game game = telegramGame.getGame();

        return i18NService.get("GAME_CREATED_GROUP") + "\n" + MessageFormat.format(i18NService.get("GAME_SELECTED_MAX_PLAYER_NUMBER"), game.getMaxNumberOfPlayers());
    }

    private String getCurrentPlayerNumberMessage(TelegramGame telegramGame) {
        StringBuilder message = new StringBuilder(MessageFormat.format(i18NService.get("GAME_CREATED_CURRENT_PLAYER_NUMBER"), telegramGame.getGame().getPlayers().size()));

        for (Player player : telegramGame.getGame().getPlayers()) {
            message.append("\n").append(player.getUser().getName());
        }

        return message.toString();
    }

    private String getCurrentVoteDeletionNumberMessage(TelegramGame telegramGame) {
        return MessageFormat.format(i18NService.get("GAME_CREATED_CURRENT_VOTE_DELETION_NUMBER"), telegramGame.getGame().getDeletionVotes().size());
    }

    private String namesOf(List<Player> players) {
        return players.stream().map(player -> player.getUser().getName()).reduce((a, b) -> a + ", " + b).orElse("");
    }

}
