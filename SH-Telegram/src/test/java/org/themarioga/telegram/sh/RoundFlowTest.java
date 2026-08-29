package org.themarioga.telegram.sh;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.themarioga.commons.engine.enums.GameStatusEnum;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.engine.sh.config.GameConfig;
import org.themarioga.engine.sh.enums.LawTypeEnum;
import org.themarioga.engine.sh.enums.RoundStatusEnum;
import org.themarioga.engine.sh.models.Game;
import org.themarioga.engine.sh.models.Law;
import org.themarioga.engine.sh.models.Player;
import org.themarioga.engine.sh.models.Round;
import org.themarioga.engine.sh.service.intf.GameService;
import org.themarioga.engine.sh.service.intf.SHService;
import org.themarioga.telegram.sh.game.service.intf.SHTelegramService;
import org.themarioga.telegram.sh.support.BotFlowTest;
import org.themarioga.telegram.sh.support.RecordingBotMessageService;

import java.util.List;

/**
 * La ronda de punta a punta: nominación, votación, sesión legislativa y fin de partida.
 * <p>
 * El mazo se fuerza a leyes liberales porque el motor lo baraja: sin eso, una ley fascista
 * desbloquearía un poder ejecutivo a mitad de test y la partida se quedaría esperando a la fase S6.
 * Es también lo que permite llevar la partida hasta la victoria liberal de forma determinista.
 */
class RoundFlowTest extends BotFlowTest {

    private static final long GROUP_CHAT = -100600L;
    private static final long CREATOR = 600L;
    private static final List<Long> OTHERS = List.of(601L, 602L, 603L, 604L);

    @Autowired
    private SHTelegramService game;
    @Autowired
    private SHService shService;
    @Autowired
    private GameService gameService;
    @Autowired
    private GameConfig gameConfig;
    @Autowired
    private EntityManager entityManager;

    private final RecordingBotMessageService messages = MESSAGES;

    @BeforeEach
    void setUp() {
        givenRegisteredUser(CREATOR, "creador");
        for (long other : OTHERS) {
            givenRegisteredUser(other, "jugador" + other);
        }

        startGame();
    }

    @Test
    void theNominationKeyboardOnlyGoesToThePresident() {
        openNomination();

        long president = telegramIdOf(president());

        RecordingBotMessageService.Sent ask = messages.lastTo(president);
        Assertions.assertNotNull(ask);
        Assertions.assertFalse(ask.callbackData().isEmpty(), "el presidente tiene que recibir la lista de candidatos");
        Assertions.assertTrue(ask.callbackData().get(0).startsWith("sh_chancellor__"));

        for (RecordingBotMessageService.Sent sent : messages.sentTo(GROUP_CHAT)) {
            Assertions.assertTrue(sent.callbackData().stream().noneMatch(data -> data.startsWith("sh_chancellor__")), () -> "la elección de canciller no puede ofrecerse en el grupo: " + sent.text());
        }
    }

    @Test
    void nominatingOpensTheVoteInTheGroup() {
        nominate();

        RecordingBotMessageService.Sent vote = messages.lastTo(GROUP_CHAT);
        Assertions.assertNotNull(vote);
        Assertions.assertTrue(vote.callbackData().contains("sh_vote__YES"));
        Assertions.assertTrue(vote.callbackData().contains("sh_vote__NO"));
        Assertions.assertEquals(RoundStatusEnum.VOTING_CHANCELLOR, round().getStatus());
    }

    /**
     * Mientras la votación sigue abierta solo se dice cuántos han votado: quién ha votado qué se
     * revela de golpe al cerrarse, como en la mesa de verdad.
     */
    @Test
    void anOpenVoteDoesNotRevealWhoVotedWhat() {
        nominate();
        messages.clear();

        voteAs(CREATOR, "YES");

        RecordingBotMessageService.Sent progress = messages.lastTo(GROUP_CHAT);
        Assertions.assertNotNull(progress);
        Assertions.assertTrue(progress.text().contains("1"), "hay que decir cuántos han votado: " + progress.text());
        // El sentido del voto solo aparece en el recuento final, que lista "jugador: Ja/Nein"
        Assertions.assertFalse(progress.text().contains("Ja!") || progress.text().contains("Nein!"), "pero no qué ha votado: " + progress.text());
    }

    @Test
    void aPassedVoteOpensTheLegislativeSessionInPrivate() {
        nominate();
        voteAll("YES");

        Assertions.assertEquals(RoundStatusEnum.PRESIDENT_DISCARDING_LAW, round().getStatus());

        RecordingBotMessageService.Sent laws = messages.lastTo(telegramIdOf(president()));
        Assertions.assertNotNull(laws);
        Assertions.assertEquals(3, laws.callbackData().size(), "el presidente ve las tres leyes");
        Assertions.assertTrue(laws.callbackData().get(0).startsWith("sh_discard__"));

        for (RecordingBotMessageService.Sent sent : messages.sentTo(GROUP_CHAT)) {
            Assertions.assertTrue(sent.callbackData().stream().noneMatch(data -> data.startsWith("sh_discard__")), () -> "las leyes del presidente no pueden acabar en el grupo: " + sent.text());
        }
    }

    @Test
    void aRejectedGovernmentAdvancesTheElectionTracker() {
        nominate();
        voteAll("NO");

        Assertions.assertEquals(RoundStatusEnum.CHANCELLOR_REJECTED, round().getStatus());

        RecordingBotMessageService.Sent result = messages.lastTo(GROUP_CHAT);
        Assertions.assertTrue(result.callbackData().contains("sh_next_round"), "hay que poder seguir a la ronda siguiente");

        nextRound();

        Assertions.assertEquals(1, gameService.getByRoom(room()).getFailedVotationCounter());
    }

    /**
     * La regla del caos: tres elecciones fallidas seguidas promulgan solas la ley de arriba del
     * mazo. El motor lo hace dentro de nextRound sin avisar, así que el bot lo detecta comparando el
     * recuento de leyes.
     */
    @Test
    void threeFailedElectionsEnactALawOnTheirOwn() {
        for (int i = 0; i < 3; i++) {
            openNomination();
            nominate();
            voteAll("NO");
            messages.clear();
            nextRound();
        }

        Game current = gameService.getByRoom(room());
        Assertions.assertEquals(1, current.getNumberOfLiberalLawsEnacted(), "la tercera elección fallida promulga sola");
        Assertions.assertEquals(0, current.getFailedVotationCounter(), "y el contador se reinicia");

        Assertions.assertTrue(messages.sentTo(GROUP_CHAT).stream().anyMatch(sent -> sent.text().contains("caos")), "hay que contarlo en el grupo");
    }

    /**
     * El criterio de aceptación de la fase: una partida entera, ronda a ronda, hasta la victoria
     * liberal.
     */
    @Test
    void aFullGameIsPlayedUntilTheLiberalsWin() {
        for (int law = 1; law <= gameConfig.getLiberalLawsToWin(); law++) {
            openNomination();
            nominate();
            voteAll("YES");
            discardFirstLaw();
            enactFirstLaw();

            if (law < gameConfig.getLiberalLawsToWin()) {
                Assertions.assertEquals(law, gameService.getByRoom(room()).getNumberOfLiberalLawsEnacted());
                nextRound();
            }
        }

        Assertions.assertNull(gameService.getByRoom(room()), "al terminar, la partida se borra");

        Assertions.assertTrue(messages.sentTo(GROUP_CHAT).stream().anyMatch(sent -> sent.text().contains("liberales")), "hay que anunciar quién gana");
        Assertions.assertTrue(messages.sentTo(GROUP_CHAT).stream().anyMatch(sent -> sent.text().contains("Hitler")), "y revelar los roles, Hitler incluido");
    }

    /**
     * R2 a lo largo de toda la partida: ninguna de las decisiones privadas puede ofrecerse en el
     * grupo. Es el fallo que arruina una partida de Secret Hitler y que ningún compilador detecta.
     */
    @Test
    void noPrivateDecisionIsEverOfferedInTheGroup() {
        for (int law = 1; law <= gameConfig.getLiberalLawsToWin(); law++) {
            openNomination();
            nominate();
            voteAll("YES");
            discardFirstLaw();
            enactFirstLaw();

            if (law < gameConfig.getLiberalLawsToWin()) nextRound();
        }

        for (RecordingBotMessageService.Sent sent : messages.sentTo(GROUP_CHAT)) {
            for (String data : sent.callbackData()) {
                Assertions.assertFalse(data.startsWith("sh_chancellor__") || data.startsWith("sh_discard__") || data.startsWith("sh_enact__"), () -> "decisión privada ofrecida en el grupo: " + data);
            }
        }
    }

    // ///////////// Apoyo //////////////////

    private Room room() {
        return roomResolver.resolveRoom(GROUP_CHAT, "Grupo de pruebas");
    }

    private Game currentGame() {
        return gameService.getByRoom(room());
    }

    private Round round() {
        return currentGame().getCurrentRound();
    }

    private Player president() {
        return round().getPresident();
    }

    private void startGame() {
        logInAs(CREATOR, GROUP_CHAT, "group");
        game.startCreatingGame(GROUP_CHAT, "Grupo de pruebas");

        for (long other : OTHERS) {
            logInAs(other, GROUP_CHAT, "group");
            game.gameJoinQuery(GROUP_CHAT, "cb");
        }

        logInAsCallback(CREATOR, GROUP_CHAT, "group");
        game.gameStartQuery(GROUP_CHAT, "cb");

        forceLiberalLaws();
    }

    /**
     * Arranca el mensaje de nominación de la ronda en curso. Tras empezar la partida y tras cada
     * nextRound el bot ya lo hace solo, así que esto solo hace falta cuando el test quiere volver a
     * pintarlo.
     */
    private void openNomination() {
        forceLiberalLaws();
    }

    private void nominate() {
        Player president = president();
        Player candidate = shService.getChancellorCandidates(currentGame()).get(0);

        long presidentChat = telegramIdOf(president);
        logInAsCallback(presidentChat, presidentChat, "private");

        game.selectChancellorQuery("cb", candidate.getId().toString());
    }

    private void voteAs(long telegramId, String vote) {
        logInAsCallback(telegramId, GROUP_CHAT, "group");

        game.voteChancellorQuery("cb", vote);
    }

    private void voteAll(String vote) {
        // Los identificadores se sacan antes de votar: cada voto vuelve a mezclar la partida en la
        // sesión de Hibernate y la colección de jugadores deja de poder recorrerse a la vez
        List<Long> voters = currentGame().getPlayers().stream().filter(Player::isAlive).map(this::telegramIdOf).toList();

        for (long voter : voters) {
            voteAs(voter, vote);
        }
    }

    private void discardFirstLaw() {
        Player president = president();
        Law law = round().getRoundAvailableLaws().get(0);

        long presidentChat = telegramIdOf(president);
        logInAsCallback(presidentChat, presidentChat, "private");

        game.presidentDiscardLawQuery("cb", law.getId().toString());
    }

    private void enactFirstLaw() {
        Player chancellor = round().getChancellor();
        Law law = round().getRoundAvailableLaws().get(0);

        long chancellorChat = telegramIdOf(chancellor);
        logInAsCallback(chancellorChat, chancellorChat, "private");

        game.chancellorEnactLawQuery("cb", law.getId().toString());
    }

    private void nextRound() {
        logInAsCallback(CREATOR, GROUP_CHAT, "group");

        game.nextRoundQuery(GROUP_CHAT, "cb");

        forceLiberalLaws();
    }

    /**
     * Deja la ronda y el mazo llenos de leyes liberales. El motor baraja, así que sin esto el test
     * dependería del azar: bastaría una ley fascista para desbloquear un poder ejecutivo y dejar la
     * partida esperando a S6.
     */
    private void forceLiberalLaws() {
        Game current = currentGame();
        if (current == null || current.getCurrentRound() == null) return;

        Round round = current.getCurrentRound();
        round.getRoundAvailableLaws().clear();
        for (int i = 0; i < 3; i++) {
            round.getRoundAvailableLaws().add(persistLaw());
        }

        current.getLawPickDeck().clear();
        current.getLawDiscardDeck().clear();
        for (int i = 0; i < 6; i++) {
            current.getLawPickDeck().add(persistLaw());
        }

        gameService.update(current);
    }

    private Law persistLaw() {
        Law law = new Law(LawTypeEnum.LIBERAL);
        entityManager.persist(law);

        return law;
    }

    private long telegramIdOf(Player player) {
        return telegramUserService.getByUser(player.getUser()).getId();
    }

}
