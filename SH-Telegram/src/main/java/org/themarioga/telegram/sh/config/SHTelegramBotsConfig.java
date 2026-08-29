package org.themarioga.telegram.sh.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.webhook.starter.SpringTelegramWebhookBot;
import org.themarioga.commons.telegram.models.UpdateInterceptor;
import org.themarioga.commons.telegram.services.impl.BotMessageServiceImpl;
import org.themarioga.commons.telegram.services.impl.LongPollingBotServiceImpl;
import org.themarioga.commons.telegram.services.impl.PendingReplyRegistry;
import org.themarioga.commons.telegram.services.impl.WebhookBotServiceImpl;
import org.themarioga.commons.telegram.services.intf.ApplicationService;
import org.themarioga.commons.telegram.services.intf.BotMessageService;
import org.themarioga.commons.telegram.services.intf.BotService;

import java.util.List;

/**
 * Da de alta el bot de Secret Hitler.
 * <p>
 * El orden de dependencias es {@code TelegramClient → BotMessageService → ApplicationService → bot},
 * en un solo sentido, como en CAH-Telegram.
 * <p>
 * Aunque aquí solo haya un bot, el modo (long-polling o webhook) se resuelve igual: con dos
 * {@code @Configuration} anidadas y {@code @ConditionalOnProperty} sobre {@code telegram.bots.type}.
 * Declarar los dos {@code @Bean} con el mismo nombre en la misma clase, distinguidos solo por la
 * condición, es lo que Spring Boot 4 ya no permite.
 */
@Configuration
public class SHTelegramBotsConfig {

    public static final String GAME_BOT = "sh";

    @Bean("shTelegramClient")
    @ConditionalOnProperty(prefix = "sh.bot", name = "enabled", havingValue = "true")
    public TelegramClient shTelegramClient(@Value("${sh.bot.token}") String token) {
        return new OkHttpTelegramClient(token);
    }

    @Bean("shBotMessageService")
    @ConditionalOnProperty(prefix = "sh.bot", name = "enabled", havingValue = "true")
    public BotMessageService shBotMessageService(@Qualifier("shTelegramClient") TelegramClient client, PendingReplyRegistry pendingReplies, @Value("${sh.bot.name}") String name) {
        return new BotMessageServiceImpl(client, name, pendingReplies);
    }

    @Configuration
    @ConditionalOnProperty(prefix = "telegram.bots", name = "type", havingValue = "longpolling", matchIfMissing = true)
    public static class LongPollingBots {

        @Bean("shBot")
        @ConditionalOnProperty(prefix = "sh.bot", name = "enabled", havingValue = "true")
        public SpringLongPollingBot shBot(@Value("${sh.bot.token}") String token, @Value("${sh.bot.name}") String name, @Qualifier("shTelegramClient") TelegramClient client, @Qualifier("shBotApplicationService") ApplicationService applicationService, PendingReplyRegistry pendingReplies, List<UpdateInterceptor> interceptors) {
            return new LongPollingBotServiceImpl(token, name, client, applicationService, pendingReplies, interceptors);
        }

    }

    @Configuration
    @ConditionalOnProperty(prefix = "telegram.bots", name = "type", havingValue = "webhook")
    public static class WebhookBots {

        @Bean("shBot")
        @ConditionalOnProperty(prefix = "sh.bot", name = "enabled", havingValue = "true")
        public SpringTelegramWebhookBot shBot(@Value("${sh.bot.token}") String token, @Value("${sh.bot.name}") String name, @Value("${sh.bot.webhook.url}") String webhookUrl, @Value("${sh.bot.webhook.cert.path:}") String certPath, @Qualifier("shTelegramClient") TelegramClient client, @Qualifier("shBotApplicationService") ApplicationService applicationService, PendingReplyRegistry pendingReplies, List<UpdateInterceptor> interceptors) {
            BotService botService = new WebhookBotServiceImpl(token, name, webhookUrl, certPath, client, applicationService, pendingReplies, interceptors);

            return (SpringTelegramWebhookBot) botService.getBean();
        }

    }

}
