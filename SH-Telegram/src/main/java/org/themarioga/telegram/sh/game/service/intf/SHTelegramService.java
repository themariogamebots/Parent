package org.themarioga.telegram.sh.game.service.intf;

/**
 * Comportamiento del bot de Secret Hitler.
 * <p>
 * Los {@code chatId} son identificadores de chat de Telegram, no del motor: la sala se resuelve por
 * la tabla de equivalencias. Los métodos que no reciben chat trabajan sobre el que trae la sesión
 * ({@code TelegramSecurityUtils}), que en la mayoría de las acciones de ronda es el privado del
 * jugador.
 */
public interface SHTelegramService {

    // ///////////// Usuario //////////////////

    void registerUser(org.telegram.telegrambots.meta.api.objects.User from);

    /**
     * Comprueba que la petición trae sesión. No la monta: de eso se encarga el interceptor de
     * updates.
     *
     * @param telegramId ignorado; la sesión sale del contexto de seguridad
     */
    void loginUser(long telegramId);

    void changeUserLanguageMessage();

    void changeUserLanguage(int messageId, String lang);

    // ///////////// Creación y configuración //////////////////

    void startCreatingGame(long chatId, String chatTitle);

    void gameMenuQuery(long chatId, String callbackQueryId);

    void gameConfigureQuery(long chatId, String callbackQueryId);

    void gameSelectMaxPlayersQuery(long chatId, String callbackQueryId);

    void gameChangeMaxPlayers(long chatId, String callbackQueryId, String data);

    /** Lista de jugadores a los que el creador puede echar del lobby. */
    void gameSelectKickQuery(long chatId, String callbackQueryId);

    void gameKickPlayer(long chatId, String callbackQueryId, String data);

    // ///////////// Jugadores y arranque //////////////////

    void gameJoinQuery(long chatId, String callbackQueryId);

    void leaveGame(String callbackQueryId);

    void gameStartQuery(long chatId, String callbackQueryId);

    // ///////////// Ronda //////////////////

    /** El presidente nomina canciller, desde su privado. */
    void selectChancellorQuery(String callbackQueryId, String data);

    /** Voto Ja/Nein, desde el mensaje del grupo. */
    void voteChancellorQuery(String callbackQueryId, String data);

    /** El presidente descarta una de las tres leyes, desde su privado. */
    void presidentDiscardLawQuery(String callbackQueryId, String data);

    /** El canciller promulga una de las dos leyes, desde su privado. */
    void chancellorEnactLawQuery(String callbackQueryId, String data);

    /** El canciller propone tirar las dos leyes, desde su privado y solo con el veto desbloqueado. */
    void proposeVetoQuery(String callbackQueryId);

    /** El presidente acepta o rechaza el veto, desde su privado. */
    void resolveVetoQuery(String callbackQueryId, String data);

    /**
     * Poder ejecutivo elegido por el presidente, por nombre de {@code RoundActionsEnum}.
     * <p>
     * Sirve para dos cosas: elegir cuando hay más de un poder disponible y disparar espiar el mazo,
     * que no elige a nadie pero necesita que la sesión sea la del presidente.
     */
    void selectActionQuery(String callbackQueryId, String data);

    /** El presidente investiga la lealtad de un jugador; el resultado no sale de su privado. */
    void investigatePlayerQuery(String callbackQueryId, String data);

    /** El presidente nombra a quien presidirá la ronda siguiente. */
    void callSpecialElectionQuery(String callbackQueryId, String data);

    /** El presidente ejecuta a un jugador; si era Hitler, se acaba la partida. */
    void killPlayerQuery(String callbackQueryId, String data);

    /** Cierra el turno de debate y arranca la ronda siguiente. */
    void nextRoundQuery(long chatId, String callbackQueryId);

    // ///////////// Borrado //////////////////

    /**
     * Botón de borrar del grupo. Para el creador borra la partida; para el resto, con la partida ya
     * en marcha, es un voto para borrarla.
     */
    void gameDeleteGroupQuery(long chatId, String callbackQueryId);

    void gameDeletePrivateQuery(String callbackQueryId);

    // ///////////// Administración //////////////////

    void deleteMyGames();

    void deleteGameByCreatorUsername(String username);

    void deleteAllGames();

    void sendMessageToEveryone(String message);

    void toggleGlobalMessages();

    // ///////////// Ayuda //////////////////

    void sendHelpMessage(long chatId);

}
