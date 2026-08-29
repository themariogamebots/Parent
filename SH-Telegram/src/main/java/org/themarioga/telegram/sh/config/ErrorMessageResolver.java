package org.themarioga.telegram.sh.config;

import org.springframework.stereotype.Component;
import org.themarioga.commons.engine.enums.ErrorEnum;
import org.themarioga.commons.engine.exceptions.ApplicationException;
import org.themarioga.commons.engine.services.intf.I18NService;

/**
 * Traduce los errores del motor al texto que ve el usuario.
 * <p>
 * Todas las excepciones del motor llevan su {@link ErrorEnum}, así que basta una convención:
 * {@code ROUND_WRONG_STATUS} se traduce con el tag {@code ERROR_ROUND_WRONG_STATUS}. A diferencia
 * del de CAH, aquí no hace falta tabla de excepciones a la convención: el catálogo i18n de SH se
 * escribió después que los enums y sus nombres se eligieron para cuadrar.
 */
@Component
public class ErrorMessageResolver {

    private static final String FALLBACK_TAG = "UNKNOWN_ERROR";

    private final I18NService i18NService;

    public ErrorMessageResolver(I18NService i18NService) {
        this.i18NService = i18NService;
    }

    /**
     * Texto traducido para una excepción, en el idioma del usuario de la sesión.
     * <p>
     * Si el error no tiene texto se devuelve el genérico: {@code I18NService} devuelve el propio
     * nombre del tag cuando no lo encuentra, y enseñarle "ERROR_USER_ID_EMPTY" a un usuario es peor
     * que decirle que algo ha fallado. Los que no tienen texto son validaciones internas que no
     * deberían llegar hasta aquí.
     */
    public String resolve(Throwable e) {
        if (e instanceof ApplicationException applicationException) {
            String tag = tagOf(applicationException.getErrorEnum());
            String text = i18NService.get(tag);

            if (!tag.equals(text)) return text;
        }

        return i18NService.get(FALLBACK_TAG);
    }

    public String tagOf(ErrorEnum error) {
        if (error == null) return FALLBACK_TAG;

        return "ERROR_" + ((Enum<?>) error).name();
    }

}
