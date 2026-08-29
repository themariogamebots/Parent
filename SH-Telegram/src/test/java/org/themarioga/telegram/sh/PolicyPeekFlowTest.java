package org.themarioga.telegram.sh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.themarioga.engine.sh.enums.RoundStatusEnum;
import org.themarioga.telegram.sh.support.PlayedGameTest;
import org.themarioga.telegram.sh.support.RecordingBotMessageService;

import java.util.List;

/**
 * Espiar el mazo, que es el único poder que solo sale en partidas pequeñas: con cinco o seis
 * jugadores lo desbloquea la tercera ley fascista, y con siete o más no aparece nunca.
 * <p>
 * Es también el único poder que no elige a nadie y aun así necesita un botón: el motor comprueba
 * quién actúa contra el usuario de la sesión, y la sesión de quien acaba de promulgar es la del
 * canciller. Sin ese botón el bot no tendría con qué disparar el poder en nombre del presidente.
 */
class PolicyPeekFlowTest extends PlayedGameTest {

    private static final long GROUP_CHAT = -100800L;
    private static final long CREATOR = 800L;
    private static final List<Long> OTHERS = List.of(801L, 802L, 803L, 804L);

    @Override
    protected long groupChat() {
        return GROUP_CHAT;
    }

    @Override
    protected long creator() {
        return CREATOR;
    }

    @Override
    protected List<Long> others() {
        return OTHERS;
    }

    @BeforeEach
    void setUp() {
        startGame();
    }

    @Test
    void theThirdFascistLawOffersThePeekToThePresident() {
        enactFascistLaws(3);

        Assertions.assertEquals(RoundStatusEnum.DOING_ADDITIONAL_ACTION, round().getStatus());

        RecordingBotMessageService.Sent ask = messages.lastTo(telegramIdOf(president()));
        Assertions.assertEquals(List.of("sh_action__POLICY_PEEK"), ask.callbackData(), () -> "el presidente tiene que poder espiar el mazo: " + ask.text());
    }

    @Test
    void peekingShowsTheLawsOnlyToThePresident() {
        enactFascistLaws(3);

        long presidentChat = telegramIdOf(president());

        resolvePendingPower();

        RecordingBotMessageService.Sent result = messages.lastTo(presidentChat);
        Assertions.assertTrue(result.text().contains("próximas leyes del mazo son"), () -> "el presidente tiene que ver las leyes: " + result.text());

        for (RecordingBotMessageService.Sent sent : groupMessages()) {
            Assertions.assertFalse(sent.text().contains("próximas leyes del mazo son"), () -> "las leyes espiadas no pueden llegar al grupo: " + sent.text());
        }

        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("espía las tres próximas leyes")), "en el grupo sí se dice que las ha espiado");
        Assertions.assertEquals(RoundStatusEnum.ARGUING, round().getStatus());
    }

}
