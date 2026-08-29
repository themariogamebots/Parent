package org.themarioga.telegram.sh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.themarioga.commons.telegram.services.impl.AuthUpdateInterceptor;
import org.themarioga.commons.telegram.services.intf.TelegramRoomResolver;

/**
 * Comprueba que el bot se cablea y arranca de verdad.
 * <p>
 * Cubre los dos problemas que en CAH-Telegram impedían arrancar la aplicación y que aquí se heredan
 * resueltos: los starters de long-polling y webhook, que registran los dos un bean
 * {@code telegramBotsApplication} y se pisan, y el ciclo de dependencias entre
 * {@code ApplicationService} y {@code BotService}.
 * <p>
 * Aquí no se prueban comandos —eso lo hace {@link SHApplicationServiceTest}—, sino el arranque.
 */
@SpringBootTest(properties = {"telegram.bots.type=longpolling", "sh.bot.enabled=true", "sh.bot.token=111:fake-token-de-pruebas", "sh.bot.name=shtestbot"})
class BotWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theBotIsRegisteredInLongPolling() {
        Assertions.assertNotNull(context.getBean("shBot", SpringLongPollingBot.class));

        Assertions.assertEquals(1, context.getBeansOfType(SpringLongPollingBot.class).size());
    }

    /**
     * Solo debe existir un objeto de aplicación, el que crea nuestro registrador. Si reaparecieran
     * las autoconfiguraciones de los starters, el contexto ni siquiera llegaría hasta aquí.
     */
    @Test
    void thereIsExactlyOneBotApplication() {
        Assertions.assertEquals(1, context.getBeansOfType(TelegramBotsLongPollingApplication.class).size());
        Assertions.assertFalse(context.containsBean("telegramBotsApplication"));
    }

    /**
     * El interceptor de sesión tiene que llegar al bot: sin él no habría usuario en el contexto de
     * seguridad y el motor rechazaría cualquier acción.
     */
    @Test
    void authInterceptorIsAvailableForTheBot() {
        Assertions.assertEquals(1, context.getBeansOfType(AuthUpdateInterceptor.class).size());
    }

    @Test
    void theBotHasItsClientAndMessageService() {
        Assertions.assertNotNull(context.getBean("shTelegramClient"));
        Assertions.assertNotNull(context.getBean("shBotMessageService"));
    }

    /**
     * Sin resolutor de salas, {@code TelegramContext.getRoom()} devolvería siempre null y el motor
     * no encontraría partida en ningún grupo.
     */
    @Test
    void theRoomResolverIsRegistered() {
        Assertions.assertEquals(1, context.getBeansOfType(TelegramRoomResolver.class).size());
    }

}
