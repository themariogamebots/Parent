package org.themarioga.telegram.sh.support;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.engine.sh.config.GameConfig;
import org.themarioga.engine.sh.enums.LawTypeEnum;
import org.themarioga.engine.sh.enums.PartyEnum;
import org.themarioga.engine.sh.enums.RoleEnum;
import org.themarioga.engine.sh.enums.RoundStatusEnum;
import org.themarioga.engine.sh.models.Game;
import org.themarioga.engine.sh.models.Law;
import org.themarioga.engine.sh.models.Player;
import org.themarioga.engine.sh.models.Round;
import org.themarioga.engine.sh.service.intf.GameService;
import org.themarioga.engine.sh.service.intf.SHService;
import org.themarioga.telegram.sh.game.service.intf.SHTelegramService;

import java.util.List;

/**
 * Base para los tests que juegan una partida entera contra el bot: monta la mesa y ofrece los pasos
 * de una ronda (nominar, votar, descartar, promulgar, seguir) para que cada test solo escriba lo que
 * quiere comprobar.
 * <p>
 * El mazo se fuerza a mano en cada ronda porque el motor lo baraja. Sin eso no hay forma de llegar a
 * un poder ejecutivo concreto: cuál toca depende de cuántas leyes fascistas hay en la mesa y de
 * cuántos jugadores hay sentados, y las dos cosas tienen que ser exactas.
 * <p>
 * El número de jugadores decide qué poderes salen (regla real del juego), así que cada test elige el
 * suyo: con cinco o seis, espiar el mazo; con siete u ocho, investigar la lealtad y la elección
 * especial.
 */
public abstract class PlayedGameTest extends BotFlowTest {

    @Autowired
    protected SHTelegramService bot;
    @Autowired
    protected SHService shService;
    @Autowired
    protected GameService gameService;
    @Autowired
    protected GameConfig gameConfig;
    @Autowired
    protected EntityManager entityManager;

    protected final RecordingBotMessageService messages = MESSAGES;

    protected abstract long groupChat();

    protected abstract long creator();

    protected abstract List<Long> others();

    // ///////////// La mesa //////////////////

    protected void startGame() {
        registerPlayers();

        logInAs(creator(), groupChat(), "group");
        bot.startCreatingGame(groupChat(), "Grupo de pruebas");

        for (long other : others()) {
            logInAs(other, groupChat(), "group");
            bot.gameJoinQuery(groupChat(), "cb");
        }

        logInAsCallback(creator(), groupChat(), "group");
        bot.gameStartQuery(groupChat(), "cb");
    }

    /**
     * Da de alta a la mesa. Se puede sustituir para montar jugadores fuera de lo corriente —el que
     * no tiene alias de Telegram, por ejemplo—, que es donde se rompen las cosas.
     */
    protected void registerPlayers() {
        givenRegisteredUser(creator(), "creador");
        for (long other : others()) {
            givenRegisteredUser(other, "jugador" + other);
        }
    }

    protected Room room() {
        return roomResolver.resolveRoom(groupChat(), "Grupo de pruebas");
    }

    protected Game currentGame() {
        return gameService.getByRoom(room());
    }

    protected Round round() {
        Game current = currentGame();

        return current != null ? current.getCurrentRound() : null;
    }

    protected Player president() {
        return round().getPresident();
    }

    protected Player chancellor() {
        return round().getChancellor();
    }

    protected Player hitler() {
        return currentGame().getPlayers().stream().filter(player -> player.getRole() == RoleEnum.HITLER).findFirst().orElseThrow();
    }

    protected List<RecordingBotMessageService.Sent> groupMessages() {
        return messages.sentTo(groupChat());
    }

    protected long telegramIdOf(Player player) {
        return telegramUserService.getByUser(player.getUser()).getId();
    }

    // ///////////// Los pasos de una ronda //////////////////

    protected void nominate(Player candidate) {
        long presidentChat = telegramIdOf(president());
        logInAsCallback(presidentChat, presidentChat, "private");

        bot.selectChancellorQuery("cb", candidate.getId().toString());
    }

    /**
     * Nomina a alguien que no sea Hitler: con tres leyes fascistas en la mesa, elegirlo termina la
     * partida ahí mismo y el test no llegaría a donde quiere ir.
     */
    protected void nominate() {
        nominate(safeCandidate());
    }

    protected Player safeCandidate() {
        List<Player> candidates = shService.getChancellorCandidates(currentGame());

        return candidates.stream().filter(player -> player.getRole() != RoleEnum.HITLER).findFirst().orElse(candidates.get(0));
    }

    protected void voteAs(long telegramId, String vote) {
        logInAsCallback(telegramId, groupChat(), "group");

        bot.voteChancellorQuery("cb", vote);
    }

    protected void voteAll(String vote) {
        // Los identificadores se sacan antes de votar: cada voto vuelve a mezclar la partida en la
        // sesión de Hibernate y la colección de jugadores deja de poder recorrerse a la vez
        List<Long> voters = currentGame().getPlayers().stream().filter(Player::isAlive).map(this::telegramIdOf).toList();

        for (long voter : voters) {
            voteAs(voter, vote);
        }
    }

    protected void discardFirstLaw() {
        long presidentChat = telegramIdOf(president());
        Law law = round().getRoundAvailableLaws().get(0);

        logInAsCallback(presidentChat, presidentChat, "private");

        bot.presidentDiscardLawQuery("cb", law.getId().toString());
    }

    protected void enactFirstLaw() {
        long chancellorChat = telegramIdOf(chancellor());
        Law law = round().getRoundAvailableLaws().get(0);

        logInAsCallback(chancellorChat, chancellorChat, "private");

        bot.chancellorEnactLawQuery("cb", law.getId().toString());
    }

    protected void nextRound() {
        logInAsCallback(creator(), groupChat(), "group");

        bot.nextRoundQuery(groupChat(), "cb");
    }

    /** Una ronda completa que acaba promulgando una ley del tipo pedido. */
    protected void enactLaw(LawTypeEnum type) {
        forceLaws(type);
        nominate();
        voteAll("YES");
        discardFirstLaw();
        enactFirstLaw();
    }

    /**
     * Deja {@code count} leyes fascistas en la mesa. Los poderes que van saliendo por el camino se
     * resuelven solos, menos el de la última ley: ese se queda pendiente, que es justo lo que el
     * test que llama a esto viene a mirar.
     */
    protected void enactFascistLaws(int count) {
        for (int law = 1; law <= count; law++) {
            enactLaw(LawTypeEnum.FASCIST);

            if (law < count) {
                resolvePendingPower();
                nextRound();
            }
        }
    }

    /**
     * Usa el poder ejecutivo que el bot le haya ofrecido al presidente por privado, sea el que sea,
     * y devuelve la clave del callback con la que se ofreció (o {@code null} si no había ninguno).
     * <p>
     * Que el poder se lea del propio teclado que recibió el presidente no es casualidad: así el
     * test que solo quiere llegar a la ronda siguiente comprueba de paso que el bot ofrece el poder
     * que toca, y en el privado que toca.
     */
    protected String resolvePendingPower() {
        if (round() == null || round().getStatus() != RoundStatusEnum.DOING_ADDITIONAL_ACTION) return null;

        long presidentChat = telegramIdOf(president());

        RecordingBotMessageService.Sent ask = messages.lastTo(presidentChat);
        Assertions.assertNotNull(ask, "el presidente tiene que recibir el poder por privado");
        Assertions.assertFalse(ask.callbackData().isEmpty(), () -> "el poder se ofrece con botones: " + ask.text());

        String data = ask.callbackData().get(0);
        String key = data.substring(0, data.indexOf("__"));
        String payload = data.substring(data.indexOf("__") + 2);

        logInAsCallback(presidentChat, presidentChat, "private");

        switch (key) {
            case "sh_investigate" -> bot.investigatePlayerQuery("cb", payload);
            case "sh_special_election" -> bot.callSpecialElectionQuery("cb", payload);
            case "sh_kill" -> bot.killPlayerQuery("cb", survivableTarget().getId().toString());
            case "sh_action" -> bot.selectActionQuery("cb", payload);
            default -> Assertions.fail("poder ejecutivo desconocido: " + data);
        }

        return key;
    }

    /** A quién ejecutar cuando el test solo quiere seguir jugando: cualquiera menos Hitler. */
    protected Player survivableTarget() {
        return shService.getKillablePlayers(currentGame()).stream().filter(player -> player.getRole() != RoleEnum.HITLER).findFirst().orElseThrow();
    }

    // ///////////// Trucos para hacer la partida determinista //////////////////

    /**
     * Llena la ronda y el mazo de leyes de un solo tipo. El motor baraja, así que sin esto no habría
     * forma de decidir qué ley se promulga ni, por tanto, a qué poder ejecutivo se llega.
     */
    protected void forceLaws(LawTypeEnum type) {
        Game current = currentGame();
        if (current == null || current.getCurrentRound() == null) return;

        Round round = current.getCurrentRound();
        round.getRoundAvailableLaws().clear();
        for (int i = 0; i < 3; i++) {
            round.getRoundAvailableLaws().add(persistLaw(type));
        }

        current.getLawPickDeck().clear();
        current.getLawDiscardDeck().clear();
        for (int i = 0; i < 6; i++) {
            current.getLawPickDeck().add(persistLaw(type));
        }

        gameService.update(current);
    }

    /**
     * Intercambia el papel de Hitler con el del jugador indicado. El reparto de roles es aleatorio y
     * hay condiciones de victoria que dependen de quién es Hitler exactamente.
     */
    protected void makeHitler(Player player) {
        Player hitler = hitler();
        if (hitler.equals(player)) return;

        RoleEnum role = player.getRole();
        PartyEnum party = player.getParty();

        player.setRole(RoleEnum.HITLER);
        player.setParty(PartyEnum.FASCIST);
        hitler.setRole(role);
        hitler.setParty(party);

        entityManager.merge(player);
        entityManager.merge(hitler);
        entityManager.flush();
    }

    private Law persistLaw(LawTypeEnum type) {
        Law law = new Law(type);
        entityManager.persist(law);

        return law;
    }

}
