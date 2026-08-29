package org.themarioga.telegram.sh.support;

import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.themarioga.commons.telegram.services.impl.PendingReplyRegistry;
import org.themarioga.commons.telegram.services.intf.BotMessageService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sustituto de la mensajería de Telegram que apunta lo que se envía en vez de enviarlo.
 * <p>
 * Copiado de CAH-Telegram (decisión D7 del plan: compartirlo por test-jar ataría los dos
 * despliegues). En Secret Hitler tiene un uso propio y crítico: comprobar que lo secreto —roles,
 * leyes concretas, resultado de una investigación— no se cuela nunca en el chat de grupo.
 */
public class RecordingBotMessageService implements BotMessageService {

    /**
     * @param edited true si el mensaje se editó en lugar de enviarse
     */
    public record Sent(long chatId, String text, InlineKeyboardMarkup keyboard, boolean edited, Integer messageId) {

        public List<String> buttonTexts() {
            if (keyboard == null) return List.of();

            List<String> texts = new ArrayList<>();
            for (InlineKeyboardRow row : keyboard.getKeyboard()) {
                for (InlineKeyboardButton button : row) {
                    texts.add(button.getText());
                }
            }

            return texts;
        }

        public List<String> callbackData() {
            if (keyboard == null) return List.of();

            List<String> data = new ArrayList<>();
            for (InlineKeyboardRow row : keyboard.getKeyboard()) {
                for (InlineKeyboardButton button : row) {
                    data.add(button.getCallbackData());
                }
            }

            return data;
        }

    }

    private final String botName;
    private final PendingReplyRegistry pendingReplies;

    private final List<Sent> sent = new ArrayList<>();
    private final List<String> answeredCallbacks = new ArrayList<>();
    private final List<Long> deletedFrom = new ArrayList<>();
    private final AtomicInteger nextMessageId = new AtomicInteger(1000);

    public RecordingBotMessageService(String botName, PendingReplyRegistry pendingReplies) {
        this.botName = botName;
        this.pendingReplies = pendingReplies;
    }

    // ///////////// Lo que se comprueba en los tests //////////////////

    public List<Sent> sent() {
        return sent;
    }

    public List<Sent> sentTo(long chatId) {
        return sent.stream().filter(s -> s.chatId() == chatId).toList();
    }

    public Sent last() {
        return sent.isEmpty() ? null : sent.get(sent.size() - 1);
    }

    public Sent lastTo(long chatId) {
        List<Sent> toChat = sentTo(chatId);
        return toChat.isEmpty() ? null : toChat.get(toChat.size() - 1);
    }

    public List<String> answeredCallbacks() {
        return answeredCallbacks;
    }

    public List<Long> deletedFrom() {
        return deletedFrom;
    }

    /**
     * Comando que quedó esperando la respuesta de ese chat, y lo consume.
     */
    public String pendingReplyFor(long chatId) {
        return pendingReplies.poll(botName, chatId);
    }

    public void clear() {
        sent.clear();
        answeredCallbacks.clear();
        deletedFrom.clear();
    }

    // ///////////// BotMessageService //////////////////

    @Override
    public void sendMessage(long chatId, String text) {
        sent.add(new Sent(chatId, text, null, false, nextMessageId.getAndIncrement()));
    }

    @Override
    public void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
        sent.add(new Sent(chatId, text, keyboard, false, nextMessageId.getAndIncrement()));
    }

    @Override
    public void sendMessageWithForceReply(long chatId, String text) {
        sent.add(new Sent(chatId, text, null, false, nextMessageId.getAndIncrement()));
    }

    @Override
    public void setPendingReply(long chatId, String command) {
        pendingReplies.set(botName, chatId, command);
    }

    /**
     * Se completa en el hilo actual, no en otro: así los tests son deterministas. Que la sesión
     * viaje bien a otro hilo lo comprueba {@code TelegramSessionTest} por su cuenta.
     */
    @Override
    public CompletableFuture<Message> sendMessageAsync(long chatId, String text) {
        int messageId = nextMessageId.getAndIncrement();
        sent.add(new Sent(chatId, text, null, false, messageId));

        Message message = Message.builder().messageId(messageId).chat(org.telegram.telegrambots.meta.api.objects.chat.Chat.builder().id(chatId).type("private").build()).build();

        return CompletableFuture.completedFuture(message);
    }

    @Override
    public void editMessage(long chatId, int messageId, String text) {
        sent.add(new Sent(chatId, text, null, true, messageId));
    }

    @Override
    public void editMessage(long chatId, int messageId, String text, InlineKeyboardMarkup keyboard) {
        sent.add(new Sent(chatId, text, keyboard, true, messageId));
    }

    @Override
    public void deleteMessage(long chatId, int messageId) {
        deletedFrom.add(chatId);
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId) {
        answeredCallbacks.add("");
    }

    @Override
    public void answerCallbackQuery(String callbackQueryId, String text) {
        answeredCallbacks.add(text);
    }

    @Override
    public String sanitizeTextFromCommand(String command, String text) {
        return text.replace(command, "").replace("@" + botName, "").trim();
    }

}
