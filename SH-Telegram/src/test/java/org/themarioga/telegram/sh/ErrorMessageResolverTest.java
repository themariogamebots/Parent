package org.themarioga.telegram.sh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.themarioga.commons.engine.enums.CommonErrorEnum;
import org.themarioga.commons.engine.enums.ErrorEnum;
import org.themarioga.commons.engine.exceptions.ApplicationException;
import org.themarioga.engine.sh.enums.SHErrorEnum;
import org.themarioga.telegram.sh.config.ErrorMessageResolver;
import org.themarioga.telegram.sh.exceptions.TelegramErrorEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * La frontera entre los errores del motor y lo que lee el usuario.
 */
@SpringBootTest
class ErrorMessageResolverTest {

    @Autowired
    private ErrorMessageResolver errorMessageResolver;

    @Test
    void resolvesByConvention() {
        Assertions.assertEquals("ERROR_ROUND_WRONG_STATUS", errorMessageResolver.tagOf(SHErrorEnum.ROUND_WRONG_STATUS));
        Assertions.assertEquals("ERROR_ROOM_NOT_ACTIVE", errorMessageResolver.tagOf(CommonErrorEnum.ROOM_NOT_ACTIVE));
        Assertions.assertEquals("ERROR_SELECTION_INVALID", errorMessageResolver.tagOf(TelegramErrorEnum.SELECTION_INVALID));
    }

    /**
     * Todos los errores de Secret Hitler se le pueden explicar al jugador: son cosas que provoca él
     * (votar dos veces, elegir a quien no puede ser canciller, vetar sin veto). Si alguno cayera al
     * genérico, el jugador se quedaría sin saber por qué no le funciona el botón.
     */
    @Test
    void everyEngineErrorIsExplained() {
        String generic = errorMessageResolver.resolve(new IllegalStateException("boom"));

        for (SHErrorEnum error : SHErrorEnum.values()) {
            Assertions.assertNotEquals(generic, errorMessageResolver.resolve(new ApplicationException(error)), () -> "el error " + error + " se le explica al jugador como genérico");
        }
    }

    /**
     * La invariante que importa: pase lo que pase, al usuario nunca se le enseña el nombre de un tag.
     */
    @Test
    void neverLeaksATagName() {
        List<ErrorEnum> all = new ArrayList<>();
        all.addAll(List.of(SHErrorEnum.values()));
        all.addAll(List.of(CommonErrorEnum.values()));
        all.addAll(List.of(TelegramErrorEnum.values()));

        for (ErrorEnum error : all) {
            String message = errorMessageResolver.resolve(new ApplicationException(error));

            Assertions.assertFalse(message.startsWith("ERROR_") || message.equals(errorMessageResolver.tagOf(error)), () -> "el error " + error + " se filtra como nombre de tag: " + message);
        }
    }

    /**
     * Los errores del motor común que un jugador puede provocar de verdad.
     */
    @Test
    void theCommonErrorsAPlayerCanHitAreExplained() {
        String generic = errorMessageResolver.resolve(new IllegalStateException("boom"));

        for (ErrorEnum error : List.of(CommonErrorEnum.GAME_NOT_FOUND, CommonErrorEnum.GAME_ALREADY_EXISTS, CommonErrorEnum.GAME_NOT_FILLED, CommonErrorEnum.GAME_ALREADY_STARTED, CommonErrorEnum.GAME_CREATOR_CANNOT_LEAVE, CommonErrorEnum.GAME_ONLY_CREATOR_CAN_PERFORM_ACTION, CommonErrorEnum.PLAYER_NOT_FOUND, CommonErrorEnum.PLAYER_ALREADY_EXISTS, CommonErrorEnum.PLAYER_ALREADY_VOTED_DELETION, CommonErrorEnum.USER_NOT_ACTIVE, CommonErrorEnum.USER_NOT_FOUND, CommonErrorEnum.ROOM_NOT_FOUND)) {
            Assertions.assertNotEquals(generic, errorMessageResolver.resolve(new ApplicationException(error)), () -> "el error " + error + " se le explica al jugador como genérico");
        }
    }

    /**
     * Y los que son validaciones internas caen al genérico <b>a propósito</b>: si alguna vez
     * afloran, "Ha ocurrido un error inesperado" es mejor que "El usuario no puede ser nulo".
     */
    @Test
    void theInternalOnesStayGeneric() {
        String generic = errorMessageResolver.resolve(new IllegalStateException("boom"));

        for (ErrorEnum error : List.of(CommonErrorEnum.USER_EMPTY, CommonErrorEnum.USER_ID_EMPTY, CommonErrorEnum.ROOM_EMPTY, CommonErrorEnum.ROOM_ID_EMPTY, CommonErrorEnum.USER_USERNAME_EMPTY, CommonErrorEnum.ROOM_ROOMNAME_EMPTY)) {
            Assertions.assertEquals(generic, errorMessageResolver.resolve(new ApplicationException(error)), () -> "el error interno " + error + " ha dejado de ser genérico");
        }
    }

    @Test
    void unknownThrowableFallsBackToTheGenericMessage() {
        Assertions.assertEquals("Ha ocurrido un error inesperado.", errorMessageResolver.resolve(new IllegalStateException("boom")));
    }

}
