package org.themarioga.telegram.sh.game.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.themarioga.commons.engine.services.intf.I18NService;
import org.themarioga.commons.telegram.models.CallbackQueryHandler;
import org.themarioga.commons.telegram.models.CommandHandler;
import org.themarioga.commons.telegram.services.intf.ApplicationService;
import org.themarioga.commons.telegram.services.intf.BotMessageService;
import org.themarioga.commons.telegram.util.BotMessageUtils;
import org.themarioga.telegram.sh.game.service.intf.SHTelegramService;

import java.util.HashMap;
import java.util.Map;

/**
 * Tabla de comandos y callbacks del bot de Secret Hitler.
 * <p>
 * <b>Las claves son contrato.</b> Telegram guarda los botones dentro de los mensajes
 * indefinidamente, así que en cuanto el bot envíe el primer teclado, renombrar una clave rompe las
 * partidas en curso. {@code SHApplicationServiceTest} fija los dos conjuntos exactos y falla si
 * alguien los toca.
 * <p>
 * El envoltorio ({@code privateCommand}, {@code groupCommand}, {@code callback}) evita repetir en
 * cada entrada la comprobación del tipo de chat, el {@code try/catch} y el {@code answerCallbackQuery}
 * — que es en lo que se le fue medio fichero al equivalente de CAH.
 */
@Service("shBotApplicationService")
@ConditionalOnProperty(prefix = "sh.bot", name = "enabled", havingValue = "true")
public class SHApplicationServiceImpl implements ApplicationService {

    private static final Logger logger = LoggerFactory.getLogger(SHApplicationServiceImpl.class);

    private final BotMessageService botMessageService;
    private final SHTelegramService shTelegramService;
    private final I18NService i18NService;

    public SHApplicationServiceImpl(@Qualifier("shBotMessageService") BotMessageService botMessageService, SHTelegramService shTelegramService, I18NService i18NService) {
        this.botMessageService = botMessageService;
        this.shTelegramService = shTelegramService;
        this.i18NService = i18NService;
    }

    @Override
    public Map<String, CommandHandler> getBotCommands() {
        Map<String, CommandHandler> commands = new HashMap<>();

        commands.put("/start", privateCommand("/start", (message, data) -> shTelegramService.registerUser(message.getFrom())));

        commands.put("/lang", privateCommand("/lang", (message, data) -> {
            shTelegramService.loginUser(message.getFrom().getId());
            shTelegramService.changeUserLanguageMessage();
        }));

        commands.put("/create", groupCommand("/create", (message, data) -> {
            shTelegramService.loginUser(message.getFrom().getId());
            shTelegramService.startCreatingGame(message.getChat().getId(), message.getChat().getTitle());
        }));

        commands.put("/help", (message, data) -> guarded("/help", () -> shTelegramService.sendHelpMessage(message.getChatId())));

        commands.put("/deletemygames", privateCommand("/deletemygames", (message, data) -> {
            shTelegramService.loginUser(message.getFrom().getId());
            shTelegramService.deleteMyGames();
        }));

        commands.put("/deletegamebyusername", privateCommand("/deletegamebyusername", (message, data) -> {
            shTelegramService.loginUser(message.getFrom().getId());
            shTelegramService.deleteGameByCreatorUsername(botMessageService.sanitizeTextFromCommand("/deletegamebyusername", message.getText()));
        }));

        commands.put("/deleteallgames", privateCommand("/deleteallgames", (message, data) -> {
            shTelegramService.loginUser(message.getFrom().getId());
            shTelegramService.deleteAllGames();
        }));

        commands.put("/sendmessagetoeveryone", privateCommand("/sendmessagetoeveryone", (message, data) -> {
            shTelegramService.loginUser(message.getFrom().getId());
            shTelegramService.sendMessageToEveryone(botMessageService.sanitizeTextFromCommand("/sendmessagetoeveryone", message.getText()));
        }));

        commands.put("/toggleglobalmessages", privateCommand("/toggleglobalmessages", (message, data) -> {
            shTelegramService.loginUser(message.getFrom().getId());
            shTelegramService.toggleGlobalMessages();
        }));

        return commands;
    }

    @Override
    public Map<String, CallbackQueryHandler> getCallbackQueries() {
        Map<String, CallbackQueryHandler> callbacks = new HashMap<>();

        // Usuario
        callbacks.put("change_user_lang", callback("change_user_lang", (callbackQuery, data) -> shTelegramService.changeUserLanguage(callbackQuery.getMessage().getMessageId(), data != null && !data.isBlank() ? data : callbackQuery.getFrom().getLanguageCode())));

        // Lobby y configuración
        callbacks.put("game_menu", callback("game_menu", (callbackQuery, data) -> shTelegramService.gameMenuQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId())));
        callbacks.put("game_configure", callback("game_configure", (callbackQuery, data) -> shTelegramService.gameConfigureQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId())));
        callbacks.put("game_sel_max_players", callback("game_sel_max_players", (callbackQuery, data) -> shTelegramService.gameSelectMaxPlayersQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId())));
        callbacks.put("game_change_max_players", callback("game_change_max_players", (callbackQuery, data) -> shTelegramService.gameChangeMaxPlayers(callbackQuery.getMessage().getChatId(), callbackQuery.getId(), data)));
        callbacks.put("game_sel_kick", callback("game_sel_kick", (callbackQuery, data) -> shTelegramService.gameSelectKickQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId())));
        callbacks.put("game_kick", callback("game_kick", (callbackQuery, data) -> shTelegramService.gameKickPlayer(callbackQuery.getMessage().getChatId(), callbackQuery.getId(), data)));
        callbacks.put("game_join", callback("game_join", (callbackQuery, data) -> shTelegramService.gameJoinQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId())));
        callbacks.put("game_leave", callback("game_leave", (callbackQuery, data) -> shTelegramService.leaveGame(callbackQuery.getId())));
        callbacks.put("game_start", callback("game_start", (callbackQuery, data) -> shTelegramService.gameStartQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId())));

        // Ronda
        callbacks.put("sh_chancellor", callback("sh_chancellor", (callbackQuery, data) -> shTelegramService.selectChancellorQuery(callbackQuery.getId(), data)));
        callbacks.put("sh_vote", callback("sh_vote", (callbackQuery, data) -> shTelegramService.voteChancellorQuery(callbackQuery.getId(), data)));
        callbacks.put("sh_discard", callback("sh_discard", (callbackQuery, data) -> shTelegramService.presidentDiscardLawQuery(callbackQuery.getId(), data)));
        callbacks.put("sh_enact", callback("sh_enact", (callbackQuery, data) -> shTelegramService.chancellorEnactLawQuery(callbackQuery.getId(), data)));
        callbacks.put("sh_veto", callback("sh_veto", (callbackQuery, data) -> shTelegramService.proposeVetoQuery(callbackQuery.getId())));
        callbacks.put("sh_veto_resolve", callback("sh_veto_resolve", (callbackQuery, data) -> shTelegramService.resolveVetoQuery(callbackQuery.getId(), data)));

        // Poderes ejecutivos
        callbacks.put("sh_action", callback("sh_action", (callbackQuery, data) -> shTelegramService.selectActionQuery(callbackQuery.getId(), data)));
        callbacks.put("sh_investigate", callback("sh_investigate", (callbackQuery, data) -> shTelegramService.investigatePlayerQuery(callbackQuery.getId(), data)));
        callbacks.put("sh_special_election", callback("sh_special_election", (callbackQuery, data) -> shTelegramService.callSpecialElectionQuery(callbackQuery.getId(), data)));
        callbacks.put("sh_kill", callback("sh_kill", (callbackQuery, data) -> shTelegramService.killPlayerQuery(callbackQuery.getId(), data)));
        callbacks.put("sh_next_round", callback("sh_next_round", (callbackQuery, data) -> shTelegramService.nextRoundQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId())));

        // Borrado
        callbacks.put("game_delete_group", callback("game_delete_group", (callbackQuery, data) -> shTelegramService.gameDeleteGroupQuery(callbackQuery.getMessage().getChatId(), callbackQuery.getId())));
        callbacks.put("game_delete_private", callback("game_delete_private", (callbackQuery, data) -> shTelegramService.gameDeletePrivateQuery(callbackQuery.getId())));

        return callbacks;
    }

    // ///////////// Envoltorios //////////////////

    private CommandHandler privateCommand(String name, CommandHandler handler) {
        return (message, data) -> {
            if (!BotMessageUtils.isMessagePrivate(message)) {
                wrongChat(name, message, "ERROR_COMMAND_SHOULD_BE_ON_PRIVATE");
                return;
            }

            guarded(name, () -> handler.callback(message, data));
        };
    }

    private CommandHandler groupCommand(String name, CommandHandler handler) {
        return (message, data) -> {
            if (BotMessageUtils.isMessagePrivate(message)) {
                wrongChat(name, message, "ERROR_COMMAND_SHOULD_BE_ON_GROUP");
                return;
            }

            guarded(name, () -> handler.callback(message, data));
        };
    }

    /**
     * Todo callback hace lo mismo alrededor de su acción: exigir sesión, atrapar lo que salga mal y
     * contestar a Telegram para que el botón deje de girar. El {@code answerCallbackQuery} va fuera
     * del try, como en CAH: aunque la acción falle, el cliente tiene que dejar de esperar.
     */
    private CallbackQueryHandler callback(String key, CallbackQueryHandler handler) {
        return (callbackQuery, data) -> {
            guarded(key, () -> {
                shTelegramService.loginUser(callbackQuery.getFrom().getId());

                handler.callback(callbackQuery, data);
            });

            botMessageService.answerCallbackQuery(callbackQuery.getId());
        };
    }

    private void wrongChat(String command, Message message, String tag) {
        logger.error("Comando {} enviado en lugar incorrecto por {}", command, BotMessageUtils.getUserInfo(message.getFrom()));

        botMessageService.sendMessage(message.getChat().getId(), i18NService.get(tag, message.getFrom().getLanguageCode()));
    }

    private void guarded(String key, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            logger.error("Error atendiendo {}: {}", key, e.getMessage(), e);
        }
    }

}
