package org.themarioga.telegram.sh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.themarioga.commons.engine.enums.GameStatusEnum;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.commons.engine.models.User;
import org.themarioga.engine.sh.enums.RoleEnum;
import org.themarioga.engine.sh.enums.RoundStatusEnum;
import org.themarioga.engine.sh.models.Game;
import org.themarioga.engine.sh.models.Player;
import org.themarioga.engine.sh.service.intf.GameService;
import org.themarioga.telegram.sh.game.service.intf.SHTelegramService;
import org.themarioga.telegram.sh.support.BotFlowTest;
import org.themarioga.telegram.sh.services.intf.TelegramGameService;
import org.themarioga.telegram.sh.support.RecordingBotMessageService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ejercita el lobby contra la base de datos: crear partida en un grupo, unirse, configurar, echar,
 * salir, arrancar y borrar.
 * <p>
 * El test que de verdad importa aquí es {@link #nothingSecretReachesTheGroup()}: en Secret Hitler,
 * mandar al grupo algo que era para un privado no rompe una pantalla, arruina la partida entera, y
 * no hay compilador que lo detecte.
 */
class GameFlowTest extends BotFlowTest {

    private static final long GROUP_CHAT = -100500L;
    private static final long CREATOR = 500L;
    private static final List<Long> OTHERS = List.of(501L, 502L, 503L, 504L, 505L, 506L);

    @Autowired
    private SHTelegramService game;
    @Autowired
    private GameService gameService;
    @Autowired
    private TelegramGameService telegramGameService;

    private final RecordingBotMessageService messages = MESSAGES;

    @BeforeEach
    void setUp() {
        givenRegisteredUser(CREATOR, "creador");
        for (long other : OTHERS) {
            givenRegisteredUser(other, "jugador" + other);
        }

        messages.clear();
    }

    @Test
    void creatingAGameWritesToTheGroupAndToTheCreator() {
        createGame();

        Assertions.assertFalse(messages.sentTo(GROUP_CHAT).isEmpty(), "el grupo tiene que enterarse");
        Assertions.assertFalse(messages.sentTo(CREATOR).isEmpty(), "y el creador por privado");

        Game created = gameService.getByRoom(room());
        Assertions.assertNotNull(created);
        Assertions.assertEquals(GameStatusEnum.CREATED, created.getStatus());
        Assertions.assertEquals(1, created.getPlayers().size(), "el creador entra ya como jugador");
    }

    @Test
    void theGroupMenuOffersJoiningAndConfiguring() {
        createGame();

        RecordingBotMessageService.Sent groupMenu = messages.lastTo(GROUP_CHAT);
        Assertions.assertNotNull(groupMenu);
        Assertions.assertTrue(groupMenu.callbackData().contains("game_join"));
        Assertions.assertTrue(groupMenu.callbackData().contains("game_configure"));
        Assertions.assertFalse(groupMenu.callbackData().contains("game_start"), "con un solo jugador todavía no se puede empezar");
    }

    /**
     * El mínimo son 5 jugadores porque la tabla de reparto de roles del motor solo cubre de 5 a 10:
     * con menos, la partida arrancaría y reventaría al repartir.
     */
    @Test
    void theStartButtonOnlyAppearsWithEnoughPlayers() {
        createGame();
        joinAs(OTHERS.get(0));
        joinAs(OTHERS.get(1));
        joinAs(OTHERS.get(2));

        Assertions.assertFalse(groupMenuAsCreator().callbackData().contains("game_start"), "con cuatro jugadores todavía no");

        joinAs(OTHERS.get(3));

        Assertions.assertTrue(groupMenuAsCreator().callbackData().contains("game_start"), "con cinco ya se puede empezar");
    }

    @Test
    void othersJoinAndAppearInTheGroupMenu() {
        createGame();
        joinAs(OTHERS.get(0));

        Assertions.assertEquals(2, gameService.getByRoom(room()).getPlayers().size());
        Assertions.assertTrue(messages.lastTo(GROUP_CHAT).text().contains("jugador501"), "el grupo lista a los que se han unido");
        Assertions.assertTrue(messages.lastTo(OTHERS.get(0)).callbackData().contains("game_leave"), "y al que se une se le ofrece salir");
    }

    @Test
    void leavingRemovesThePlayer() {
        createGame();
        joinAs(OTHERS.get(0));

        logInAsCallback(OTHERS.get(0), OTHERS.get(0), "private");
        game.leaveGame("cb");

        Assertions.assertEquals(1, gameService.getByRoom(room()).getPlayers().size());
        Assertions.assertTrue(messages.deletedFrom().contains(OTHERS.get(0)), "y se le limpia su privado");
    }

    @Test
    void onlyTheCreatorConfiguresTheGame() {
        createGame();
        joinAs(OTHERS.get(0));

        logInAsCallback(OTHERS.get(0), GROUP_CHAT, "group");
        messages.clear();
        game.gameConfigureQuery(GROUP_CHAT, "cb");

        Assertions.assertFalse(messages.answeredCallbacks().isEmpty(), "al que no es creador hay que contestarle a la pulsación");
        Assertions.assertFalse(messages.answeredCallbacks().get(0).startsWith("ERROR_"), "el aviso no está traducido");
    }

    @Test
    void theCreatorCanKickAPlayer() {
        createGame();
        joinAs(OTHERS.get(0));

        User kicked = userOf(OTHERS.get(0));

        logInAsCallback(CREATOR, GROUP_CHAT, "group");
        messages.clear();
        game.gameSelectKickQuery(GROUP_CHAT, "cb");

        List<String> options = messages.lastTo(GROUP_CHAT).callbackData();
        Assertions.assertTrue(options.contains("game_kick__" + kicked.getId()), "el creador tiene que poder elegir a quién echa");
        Assertions.assertEquals(2, options.size(), "él mismo no puede estar en la lista: solo el otro jugador y el botón de volver");

        game.gameKickPlayer(GROUP_CHAT, "cb", kicked.getId().toString());

        Assertions.assertEquals(1, gameService.getByRoom(room()).getPlayers().size());
        Assertions.assertNotNull(messages.lastTo(OTHERS.get(0)), "al expulsado hay que avisarle por privado");
    }

    /**
     * El criterio de aceptación de la fase: cinco jugadores, la partida arranca, cada uno recibe su
     * rol por privado y el grupo recibe el tablero.
     */
    @Test
    void startingDealsEveryRoleInPrivateAndTheBoardInTheGroup() {
        startedGame();

        Game started = gameService.getByRoom(room());
        Assertions.assertEquals(GameStatusEnum.STARTED, started.getStatus());
        Assertions.assertNotNull(started.getCurrentRound());
        Assertions.assertEquals(RoundStatusEnum.SELECTING_CHANCELLOR, started.getCurrentRound().getStatus());

        for (Player player : gameService.getByRoom(room()).getPlayers()) {
            Assertions.assertFalse(roleMessageOf(player).isBlank(), () -> "el jugador " + player.getUser().getName() + " no ha recibido su rol");
        }

        // El tablero es un mensaje propio del grupo, no el último: después va la nominación
        RecordingBotMessageService.Sent board = messages.sentTo(GROUP_CHAT).stream().filter(sent -> sent.text().contains("0/5")).findFirst().orElse(null);
        Assertions.assertNotNull(board, "el grupo no ha recibido el tablero");
        Assertions.assertTrue(board.text().contains("0/6"));

        // Y con el tablero puesto, la ronda arranca sola pidiendo canciller al presidente
        Assertions.assertNotNull(messages.lastTo(GROUP_CHAT));
        Assertions.assertTrue(messages.lastTo(GROUP_CHAT).text().contains("Ronda 1"), "la primera ronda tiene que abrirse sola: " + messages.lastTo(GROUP_CHAT).text());
    }

    /**
     * R2 del plan: lo secreto no puede acabar en el grupo. Ni el rol de nadie, ni la palabra que
     * solo aparece en los mensajes de rol.
     */
    @Test
    void nothingSecretReachesTheGroup() {
        startedGame();

        List<String> secrets = new ArrayList<>();
        for (Player player : gameService.getByRoom(room()).getPlayers()) {
            secrets.add(roleMessageOf(player));
        }

        for (RecordingBotMessageService.Sent sent : messages.sentTo(GROUP_CHAT)) {
            Assertions.assertFalse(sent.text().contains("Hitler"), () -> "se ha filtrado un rol al grupo: " + sent.text());

            for (String secret : secrets) {
                Assertions.assertNotEquals(secret, sent.text(), "un mensaje de rol ha acabado en el grupo");
            }
        }
    }

    /**
     * D8: con 5 o 6 jugadores Hitler conoce a los fascistas, con 7 o más no. Es regla de juego que
     * el motor no modela, así que la decide este bot al componer el mensaje.
     */
    @Test
    void hitlerOnlyKnowsTheFascistsInSmallGames() {
        startedGame();

        String hitlerMessage = roleMessageOf(hitler());

        Assertions.assertTrue(hitlerMessage.contains(fascistNames()), () -> "con 5 jugadores Hitler tiene que conocer a los fascistas: " + hitlerMessage);
    }

    @Test
    void inBigGamesHitlerIsAlone() {
        startedGame(7);

        String hitlerMessage = roleMessageOf(hitler());

        Assertions.assertFalse(hitlerMessage.contains(fascistNames()), () -> "con 7 jugadores Hitler no debe conocer a los fascistas: " + hitlerMessage);
    }

    /**
     * Los fascistas sí se conocen entre ellos y conocen a Hitler, en cualquier número de jugadores.
     */
    @Test
    void fascistsAlwaysKnowEachOtherAndHitler() {
        startedGame(7);

        Game started = gameService.getByRoom(room());
        Player fascist = started.getPlayers().stream().filter(player -> player.getRole() == RoleEnum.FASCIST).findFirst().orElseThrow();

        String message = roleMessageOf(fascist);

        Assertions.assertTrue(message.contains(hitler().getUser().getName()), () -> "un fascista tiene que saber quién es Hitler: " + message);
    }

    @Test
    void deletingTheGameCleansUpItsMessages() {
        startedGame();
        messages.clear();

        logInAsCallback(CREATOR, GROUP_CHAT, "group");
        game.gameDeleteGroupQuery(GROUP_CHAT, "cb");

        Assertions.assertNull(gameService.getByRoom(room()), "la partida tiene que desaparecer");
        for (long player : playing()) {
            Assertions.assertTrue(messages.deletedFrom().contains(player), () -> "hay que borrar el privado del jugador " + player);
        }
    }

    // ///////////// Apoyo //////////////////

    private Room room() {
        return roomResolver.resolveRoom(GROUP_CHAT, "Grupo de pruebas");
    }

    private void createGame() {
        logInAs(CREATOR, GROUP_CHAT, "group");

        game.startCreatingGame(GROUP_CHAT, "Grupo de pruebas");
    }

    private void joinAs(long telegramId) {
        logInAs(telegramId, GROUP_CHAT, "group");

        game.gameJoinQuery(GROUP_CHAT, "cb");
    }

    private List<Long> playing = List.of();

    private List<Long> playing() {
        return playing;
    }

    private void startedGame() {
        startedGame(5);
    }

    private void startedGame(int players) {
        createGame();

        List<Long> joined = new ArrayList<>();
        joined.add(CREATOR);
        for (int i = 0; i < players - 1; i++) {
            joinAs(OTHERS.get(i));
            joined.add(OTHERS.get(i));
        }
        playing = List.copyOf(joined);

        logInAsCallback(CREATOR, GROUP_CHAT, "group");
        messages.clear();

        game.gameStartQuery(GROUP_CHAT, "cb");
    }

    private RecordingBotMessageService.Sent groupMenuAsCreator() {
        logInAsCallback(CREATOR, GROUP_CHAT, "group");
        messages.clear();

        game.gameMenuQuery(GROUP_CHAT, "cb");

        return messages.lastTo(GROUP_CHAT);
    }

    private Player hitler() {
        return gameService.getByRoom(room()).getPlayers().stream().filter(player -> player.getRole() == RoleEnum.HITLER).findFirst().orElseThrow();
    }

    private String fascistNames() {
        return gameService.getByRoom(room()).getPlayers().stream().filter(player -> player.getRole() == RoleEnum.FASCIST).map(player -> player.getUser().getName()).reduce((a, b) -> a + ", " + b).orElseThrow();
    }

    /**
     * El mensaje de rol de un jugador, localizado por su identificador. Desde S5 no vale con mirar
     * el último mensaje de su privado: al presidente le llega después la petición de canciller.
     */
    private String roleMessageOf(Player player) {
        Integer roleMessageId = telegramGameService.getByPlayer(player).getRoleMessageId();

        return messages.sentTo(telegramIdOf(player)).stream().filter(sent -> Objects.equals(sent.messageId(), roleMessageId)).reduce((first, last) -> last).orElseThrow(() -> new AssertionError("el jugador " + player.getUser().getName() + " no ha recibido su rol")).text();
    }

    private long telegramIdOf(Player player) {
        return telegramUserService.getByUser(player.getUser()).getId();
    }

    private User userOf(long telegramId) {
        return telegramUserService.getByTelegramId(telegramId).getUser();
    }

}
