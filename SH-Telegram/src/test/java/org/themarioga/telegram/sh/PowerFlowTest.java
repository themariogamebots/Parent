package org.themarioga.telegram.sh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.themarioga.engine.sh.enums.LawTypeEnum;
import org.themarioga.engine.sh.enums.RoundStatusEnum;
import org.themarioga.engine.sh.models.Player;
import org.themarioga.telegram.sh.support.PlayedGameTest;
import org.themarioga.telegram.sh.support.RecordingBotMessageService;

import java.util.List;

/**
 * Los poderes ejecutivos y el veto, con siete jugadores en la mesa: es el tamaño en el que salen
 * investigar la lealtad (segunda ley fascista) y la elección especial (tercera), además de la
 * ejecución (cuarta) y el veto (quinta). Espiar el mazo solo sale en partidas pequeñas y se prueba
 * aparte, en {@code PolicyPeekFlowTest}.
 * <p>
 * Lo que de verdad se vigila aquí es lo mismo de siempre: que lo que un poder revela —el partido de
 * quien investigas, las leyes que espías— no aparezca jamás en el grupo.
 */
class PowerFlowTest extends PlayedGameTest {

    private static final long GROUP_CHAT = -100700L;
    private static final long CREATOR = 700L;
    private static final List<Long> OTHERS = List.of(701L, 702L, 703L, 704L, 705L, 706L);

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

    // ///////////// Investigar la lealtad //////////////////

    @Test
    void theSecondFascistLawLetsThePresidentInvestigateSomeone() {
        enactFascistLaws(2);

        Assertions.assertEquals(RoundStatusEnum.DOING_ADDITIONAL_ACTION, round().getStatus());

        RecordingBotMessageService.Sent ask = messages.lastTo(telegramIdOf(president()));
        Assertions.assertTrue(ask.callbackData().stream().allMatch(data -> data.startsWith("sh_investigate__")), () -> "el presidente tiene que recibir a quién investigar: " + ask.callbackData());
    }

    /**
     * El resultado de investigar es la información más peligrosa del juego: si se filtra al grupo,
     * la partida se acabó. Al grupo solo va a quién se ha investigado.
     */
    @Test
    void investigatingRevealsThePartyOnlyToThePresident() {
        enactFascistLaws(2);

        long presidentChat = telegramIdOf(president());

        resolvePendingPower();

        RecordingBotMessageService.Sent result = messages.lastTo(presidentChat);
        Assertions.assertTrue(result.text().contains("pertenece al partido"), () -> "el presidente tiene que ver el partido: " + result.text());

        for (RecordingBotMessageService.Sent sent : groupMessages()) {
            Assertions.assertFalse(sent.text().contains("pertenece al partido"), () -> "el resultado de investigar no puede llegar al grupo: " + sent.text());
        }

        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("investiga la lealtad")), "en el grupo sí se dice a quién se ha investigado");
        Assertions.assertEquals(RoundStatusEnum.ARGUING, round().getStatus());
    }

    // ///////////// Elección especial //////////////////

    @Test
    void theSpecialElectionChoosesWhoPresidesTheNextRound() {
        enactFascistLaws(3);

        RecordingBotMessageService.Sent ask = messages.lastTo(telegramIdOf(president()));
        Assertions.assertTrue(ask.callbackData().stream().allMatch(data -> data.startsWith("sh_special_election__")), () -> "el presidente tiene que elegir al siguiente: " + ask.callbackData());

        String chosenId = ask.callbackData().get(0).substring("sh_special_election__".length());

        resolvePendingPower();

        nextRound();

        Assertions.assertEquals(chosenId, president().getId().toString(), "el elegido preside la ronda siguiente");
        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("elección especial")), "hay que contarlo en el grupo");
    }

    // ///////////// Ejecución //////////////////

    @Test
    void executingAPlayerLeavesThemOutOfTheGame() {
        enactFascistLaws(4);

        Player target = survivableTarget();
        long targetId = telegramIdOf(target);

        long aliveBefore = currentGame().getPlayers().stream().filter(Player::isAlive).count();

        resolvePendingPower();

        Assertions.assertEquals(aliveBefore - 1, currentGame().getPlayers().stream().filter(Player::isAlive).count());
        Assertions.assertFalse(currentGame().getPlayers().stream().anyMatch(player -> player.isAlive() && telegramIdOf(player) == targetId));
        Assertions.assertEquals(RoundStatusEnum.ARGUING, round().getStatus());
        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("ejecuta a")), "hay que contarlo en el grupo");
    }

    @Test
    void executingHitlerWinsTheGameForTheLiberals() {
        enactFascistLaws(4);

        long presidentChat = telegramIdOf(president());
        Player hitler = hitler();

        logInAsCallback(presidentChat, presidentChat, "private");
        bot.killPlayerQuery("cb", hitler.getId().toString());

        Assertions.assertNull(currentGame(), "al terminar, la partida se borra");
        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("Hitler ha sido ejecutado")), "hay que anunciar por qué se acaba");
        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("Ganan los liberales")), "y quién gana");
    }

    // ///////////// Veto //////////////////

    /**
     * La quinta ley fascista trae dos poderes a la vez, pero solo uno se elige: el veto lo activa el
     * motor por su cuenta, así que al presidente se le sigue ofreciendo la ejecución a secas.
     */
    @Test
    void theFifthFascistLawUnlocksTheVetoAndStillOffersTheExecution() {
        enactFascistLaws(5);

        Assertions.assertTrue(currentGame().getVetoIsActive(), "el veto queda desbloqueado");
        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("el veto queda desbloqueado")), "hay que contarlo en el grupo");

        RecordingBotMessageService.Sent ask = messages.lastTo(telegramIdOf(president()));
        Assertions.assertTrue(ask.callbackData().stream().allMatch(data -> data.startsWith("sh_kill__")), () -> "solo se elige la ejecución: " + ask.callbackData());
    }

    @Test
    void theChancellorCanProposeAVetoAndThePresidentAcceptsIt() {
        openLegislativeSessionWithVeto();

        long chancellorChat = telegramIdOf(chancellor());
        long presidentChat = telegramIdOf(president());

        RecordingBotMessageService.Sent laws = messages.lastTo(chancellorChat);
        Assertions.assertTrue(laws.callbackData().contains("sh_veto"), () -> "el canciller tiene que poder vetar: " + laws.callbackData());

        logInAsCallback(chancellorChat, chancellorChat, "private");
        bot.proposeVetoQuery("cb");

        Assertions.assertEquals(RoundStatusEnum.VETO_REQUESTED, round().getStatus());

        RecordingBotMessageService.Sent ask = messages.lastTo(presidentChat);
        Assertions.assertEquals(List.of("sh_veto_resolve__YES", "sh_veto_resolve__NO"), ask.callbackData(), "decide el presidente, y desde su privado");

        logInAsCallback(presidentChat, presidentChat, "private");
        bot.resolveVetoQuery("cb", "YES");

        Assertions.assertEquals(RoundStatusEnum.CHANCELLOR_REJECTED, round().getStatus(), "un veto aceptado cuenta como gobierno fallido");
        Assertions.assertTrue(messages.lastTo(GROUP_CHAT).callbackData().contains("sh_next_round"));

        int fascistLawsBefore = currentGame().getNumberOfFascistLawsEnacted();

        nextRound();

        Assertions.assertEquals(1, currentGame().getFailedVotationCounter(), "y mueve el contador de elecciones fallidas");
        Assertions.assertEquals(fascistLawsBefore, currentGame().getNumberOfFascistLawsEnacted(), "sin promulgar nada");
    }

    @Test
    void aRejectedVetoSendsTheChancellorBackToTheLaws() {
        openLegislativeSessionWithVeto();

        long chancellorChat = telegramIdOf(chancellor());
        long presidentChat = telegramIdOf(president());

        logInAsCallback(chancellorChat, chancellorChat, "private");
        bot.proposeVetoQuery("cb");

        logInAsCallback(presidentChat, presidentChat, "private");
        bot.resolveVetoQuery("cb", "NO");

        Assertions.assertEquals(RoundStatusEnum.CHANCELLOR_SELECTING_LAW, round().getStatus());

        RecordingBotMessageService.Sent laws = messages.lastTo(chancellorChat);
        Assertions.assertFalse(laws.callbackData().contains("sh_veto"), "vetado y rechazado, ya no se puede volver a vetar");
        Assertions.assertTrue(laws.callbackData().stream().allMatch(data -> data.startsWith("sh_enact__")), () -> "y tiene que promulgar una de las dos: " + laws.callbackData());

        int liberalLawsBefore = currentGame().getNumberOfLiberalLawsEnacted();

        enactFirstLaw();

        Assertions.assertEquals(liberalLawsBefore + 1, currentGame().getNumberOfLiberalLawsEnacted());
    }

    // ///////////// Finales //////////////////

    @Test
    void sixFascistLawsWinTheGameForTheFascists() {
        enactFascistLaws(gameConfig.getFascistLawsToWin());

        Assertions.assertNull(currentGame(), "al terminar, la partida se borra");
        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("Ganan los fascistas")), "hay que anunciar quién gana");
        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("Los roles eran")), "y revelar los roles");
    }

    @Test
    void electingHitlerChancellorWinsTheGameForTheFascists() {
        enactFascistLaws(3);
        resolvePendingPower();
        nextRound();
        forceLaws(LawTypeEnum.FASCIST);

        // Hitler tiene que ser candidato para poder salir elegido, y quién es Hitler lo reparte el
        // motor al azar: se le coloca el papel a alguien que sí lo sea
        Player candidate = shService.getChancellorCandidates(currentGame()).get(0);
        makeHitler(candidate);

        nominate(candidate);
        voteAll("YES");

        Assertions.assertNull(currentGame(), "la votación misma termina la partida");
        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("elegido canciller")), "hay que anunciar por qué se acaba");
        Assertions.assertTrue(groupMessages().stream().anyMatch(sent -> sent.text().contains("Ganan los fascistas")), "y quién gana");
    }

    // ///////////// R2 a lo largo de todos los poderes //////////////////

    @Test
    void noPowerIsEverOfferedOrRevealedInTheGroup() {
        enactFascistLaws(5);
        resolvePendingPower();

        for (RecordingBotMessageService.Sent sent : groupMessages()) {
            for (String data : sent.callbackData()) {
                Assertions.assertFalse(data.startsWith("sh_investigate__") || data.startsWith("sh_special_election__") || data.startsWith("sh_kill__") || data.startsWith("sh_action__") || data.startsWith("sh_veto_resolve__") || data.equals("sh_veto"), () -> "poder ofrecido en el grupo: " + data);
            }

            Assertions.assertFalse(sent.text().contains("pertenece al partido") || sent.text().contains("próximas leyes del mazo son"), () -> "lo que revela un poder no puede llegar al grupo: " + sent.text());
        }
    }

    // ///////////// Apoyo //////////////////

    /**
     * Deja la partida con el veto desbloqueado y una sesión legislativa abierta: cinco leyes
     * fascistas, ejecución resuelta y otra ronda hasta que el canciller tiene las dos leyes delante.
     */
    private void openLegislativeSessionWithVeto() {
        enactFascistLaws(5);
        resolvePendingPower();
        nextRound();

        // La última ronda va con leyes liberales a propósito: con cinco fascistas ya en la mesa, la
        // sexta terminaría la partida y no se llegaría a ver qué pasa después del veto
        forceLaws(LawTypeEnum.LIBERAL);
        nominate();
        voteAll("YES");
        discardFirstLaw();
    }

}
